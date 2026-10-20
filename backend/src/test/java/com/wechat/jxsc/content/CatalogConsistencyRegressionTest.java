package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.util.*;
import java.util.concurrent.*;

class CatalogConsistencyRegressionTest extends ContentHttpSupport {
    @Test
    void staleProductFormCannotOverwriteTermsChangedThroughPlanEndpoint() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var oldForm = product("MEMBERSHIP", "Original membership", 600);
        var created = call("POST", "/admin/products", token, oldForm, 200);
        String id = created.path("id").asText();
        var plan = created.path("membership");
        call(
                "PATCH",
                "/admin/membership/plans/" + plan.path("id").asText(),
                token,
                Map.of(
                        "version",
                        plan.path("version").asLong(),
                        "durationMonths",
                        12,
                        "status",
                        "DISABLED"),
                200);
        oldForm.put("title", "Stale product form");
        call("PATCH", "/admin/products/" + id, token, oldForm, 409);
        var unchanged = call("GET", "/admin/products/" + id, token, null, 200);
        assertThat(unchanged.path("membership").path("durationMonths").asInt()).isEqualTo(12);
        assertThat(unchanged.path("title").asText()).isEqualTo("Original membership");
        oldForm.put("membership", Map.of("durationMonths", 12));
        var edited = call("PATCH", "/admin/products/" + id, token, oldForm, 200);
        assertThat(edited.path("membership").path("version").asLong()).isEqualTo(1);
        assertThat(edited.path("membership").path("durationMonths").asInt()).isEqualTo(12);
    }

    @Test
    void draftCourseCannotBecomePublicByUnpublishingBeforeMetadataExists() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var draft = createProduct(token, "COURSE", "Course without terms", 1000);
        String id = draft.path("id").asText();
        call(
                "POST",
                "/admin/products/" + id + "/unpublish",
                token,
                Map.of("version", 0, "reason", "Invalid draft transition"),
                409);
        call("GET", "/products/" + id, null, null, 404);
        assertThat(call("GET", "/admin/products/" + id, token, null, 200).path("status").asText())
                .isEqualTo("DRAFT");
        call(
                "POST",
                "/admin/courses",
                token,
                Map.of("version", 0, "productId", id, "validityType", "DAYS", "validityDays", 30),
                200);
        var live = publish(token, draft);
        call(
                "POST",
                "/admin/products/" + id + "/unpublish",
                token,
                Map.of("version", live.path("version").asLong(), "reason", "Valid lifecycle"),
                200);
        var detail = call("GET", "/products/" + id, null, null, 200);
        assertThat(detail.path("status").asText()).isEqualTo("OFF_SALE");
        assertThat(detail.path("course").path("validityDays").asInt()).isEqualTo(30);
        assertThat(detail.path("allowedActions")).isEmpty();
    }

    @Test
    void concurrentOppositeReparentsCannotCreateACycle() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var a = createCategory(token, "A");
        var b = createCategory(token, "B");
        var aToB = category("A", "BOOK", b.path("id").asText());
        var bToA = category("B", "BOOK", a.path("id").asText());
        var results =
                raceWhileCategoryLocked(
                        a.path("id").asLong(),
                        () ->
                                httpStatus(
                                        "PATCH",
                                        "/admin/categories/" + a.path("id").asText(),
                                        token,
                                        aToB),
                        () ->
                                httpStatus(
                                        "PATCH",
                                        "/admin/categories/" + b.path("id").asText(),
                                        token,
                                        bToA));
        assertThat(results).containsExactlyInAnyOrder(200, 400);
        var finalA = call("GET", "/admin/categories/" + a.path("id").asText(), token, null, 200);
        var finalB = call("GET", "/admin/categories/" + b.path("id").asText(), token, null, 200);
        assertThat(finalA.path("parentId").isNull() || finalB.path("parentId").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT", "CHILD"})
    void categoryTypeChangeCannotRaceANewReference(String reference) throws Exception {
        String token = admin("CONTENT_EDITOR");
        var parent = createCategory(token, "Parent");
        String id = parent.path("id").asText();
        var changeType = category("Parent", "COURSE", null);
        var book = product("BOOK", "Referenced book", 100);
        book.put("categoryIds", List.of(id));
        Callable<Integer> addReference =
                reference.equals("PRODUCT")
                        ? () -> httpStatus("POST", "/admin/products", token, book)
                        : () ->
                                httpStatus(
                                        "POST",
                                        "/admin/categories",
                                        token,
                                        category("Child", "BOOK", id));
        var results =
                raceWhileCategoryLocked(
                        parent.path("id").asLong(),
                        () -> httpStatus("PATCH", "/admin/categories/" + id, token, changeType),
                        addReference);
        assertThat(results).containsExactlyInAnyOrder(200, 400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM product_category pc JOIN product p ON"
                                    + " p.id=pc.product_id JOIN category c ON c.id=pc.category_id"
                                    + " WHERE c.id=? AND p.type<>c.type",
                                Integer.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM category child JOIN category parent ON"
                                        + " parent.id=child.parent_id WHERE parent.id=? AND"
                                        + " child.type<>parent.type",
                                Integer.class,
                                id))
                .isZero();
    }

    private JsonNode createCategory(String token, String title) throws Exception {
        return call("POST", "/admin/categories", token, category(title, "BOOK", null), 200);
    }

    private Map<String, Object> category(String title, String type, String parent) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", 0);
        body.put("name", title);
        body.put("type", type);
        body.put("parentId", parent);
        body.put("sortNo", 0);
        body.put("enabled", true);
        return body;
    }

    private int httpStatus(String method, String path, String token, Object body) throws Exception {
        return mvc.perform(
                        request(HttpMethod.valueOf(method), "/api/v1" + path)
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(body)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * Gate real transactions using their database lock waits, without application hooks or sleeps
     * as synchronization.
     */
    private List<Integer> raceWhileCategoryLocked(
            long categoryId, Callable<Integer> first, Callable<Integer> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2);
                var blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock =
                    blocker.prepareStatement("SELECT id FROM category WHERE id=? FOR UPDATE")) {
                lock.setLong(1, categoryId);
                lock.executeQuery().close();
            }
            try {
                var one = pool.submit(first);
                var two = pool.submit(second);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                while (System.nanoTime() < deadline) {
                    waiting =
                            jdbc.queryForObject(
                                    "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM"
                                            + " performance_schema.data_lock_waits w JOIN"
                                            + " performance_schema.data_locks l ON"
                                            + " l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND"
                                            + " l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE()",
                                    Integer.class);
                    if (waiting >= 2) break;
                    Thread.sleep(20);
                }
                assertThat(waiting)
                        .as("Both requests reached database lock waits before release")
                        .isGreaterThanOrEqualTo(2);
                blocker.commit();
                return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            } finally {
                blocker.rollback();
            }
        }
    }
}
