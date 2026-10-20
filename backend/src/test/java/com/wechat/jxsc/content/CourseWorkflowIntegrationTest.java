package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

/** Observable HTTP/SQL boundaries for ADM-B03; run only with with_local_mysql.py. */
class CourseWorkflowIntegrationTest extends ContentHttpSupport {
    private static final String ROOT = "/admin/course-workflows";

    @Test
    void createRetryAndUnknownOutcomeLookupRecoverExactlyTheSamePair() throws Exception {
        String token = admin("CONTENT_EDITOR");
        String key = key();
        String title = "Workflow " + key;
        var body = write(key, null, title);
        var created = call("POST", ROOT, token, body, 200);
        var replay = call("POST", ROOT, token, body, 200);
        assertThat(replay).isEqualTo(created);
        assertPair(created);
        assertThat(created.path("stage").asText()).isEqualTo("DRAFT");
        assertThat(created.path("product").path("status").asText()).isEqualTo("DRAFT");
        assertThat(call("GET", ROOT + "/" + courseId(created), token, null, 200))
                .isEqualTo(created);
        var operation = call("GET", ROOT + "/operations/" + key, token, null, 200);
        assertThat(operation.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(operation.path("action").asText()).isEqualTo("CREATE");
        assertThat(operation.path("result")).isEqualTo(created);
        String otherEditor = admin("CONTENT_EDITOR");
        call("GET", ROOT + "/operations/" + key, otherEditor, null, 404);
        assertThat(call("GET", ROOT + "/" + courseId(created), otherEditor, null, 200))
                .isEqualTo(created);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM course_workflow_operation WHERE operation_key=?", key))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE course_workflow_operation SET action='SAVE' WHERE operation_key=?", key))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM product WHERE title=?", Long.class, title))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM course WHERE product_id=?", Long.class,
                        productId(created)))
                .isEqualTo(1);
        body.put("product", product("COURSE", title + " changed", 8900));
        assertThat(call("POST", ROOT, token, body, 409).path("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        call("GET", ROOT + "/operations/" + key(), token, null, 404);
    }

    @Test
    void concurrentCreateRetriesCommitOnePairAndOneAudit() throws Exception {
        String token = admin("CONTENT_EDITOR");
        String key = key();
        String title = "Concurrent workflow " + key;
        byte[] payload = json.writeValueAsBytes(write(key, null, title));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var response = mvc.perform(post("/api/v1" + ROOT)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json").content(payload))
                            .andReturn().getResponse();
                    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
                    return json.readTree(response.getContentAsByteArray()).path("data");
                }));
            }
            start.countDown();
            var first = futures.get(0).get(20, TimeUnit.SECONDS);
            assertThat(futures.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(first);
            assertPair(first);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM product WHERE title=?", Long.class, title))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM operation_audit_log WHERE action='COURSE_CREATE'"
                                    + " AND resource_id=?", Long.class, courseId(first)))
                    .isEqualTo(1);
        }
    }

    @Test
    void saveIsAtomicOnCourseValidationFailureAndChecksBothVersions() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var created = call("POST", ROOT, token, write(key(), null, "Original workflow"), 200);
        String path = ROOT + "/" + courseId(created);
        String failedKey = key();
        var invalid = write(failedKey, created, "Must roll back");
        var terms = course(created.path("course").path("version").asLong());
        terms.put("validityType", "DAYS");
        // Structurally valid JSON, but inconsistent course terms fail inside the transaction.
        invalid.put("course", terms);
        call("PUT", path, token, invalid, 400);
        assertThat(call("GET", path, token, null, 200)).isEqualTo(created);
        call("GET", ROOT + "/operations/" + failedKey, token, null, 404);

        String saveKey = key();
        var request = write(saveKey, created, "Saved together");
        var saved = call("PUT", path, token, request, 200);
        assertThat(productId(saved)).isEqualTo(productId(created));
        assertThat(courseId(saved)).isEqualTo(courseId(created));
        assertThat(saved.path("product").path("title").asText()).isEqualTo("Saved together");
        assertThat(call("PUT", path, token, request, 200)).isEqualTo(saved);
        assertThat(call("GET", ROOT + "/operations/" + saveKey, token, null, 200)
                        .path("result"))
                .isEqualTo(saved);

        var staleProduct = write(key(), saved, "Stale product");
        var product = product("COURSE", "Stale product", 8900);
        product.put("version", created.path("product").path("version").asLong());
        staleProduct.put("product", product);
        assertThat(call("PUT", path, token, staleProduct, 409).path("code").asText())
                .isEqualTo("VERSION_CONFLICT");

        // A chapter commit advances course.version independently of product.version.
        call("POST", path + "/chapters", token, chapter(key()), 200);
        var afterChapter = call("GET", path, token, null, 200);
        assertThat(afterChapter.path("course").path("version").asLong())
                .isGreaterThan(saved.path("course").path("version").asLong());
        var staleCourse = write(key(), afterChapter, "Stale course");
        staleCourse.put("course", course(saved.path("course").path("version").asLong()));
        call("PUT", path, token, staleCourse, 409);
        assertThat(call("GET", path, token, null, 200)).isEqualTo(afterChapter);
    }

    @Test
    void failedCreateLeavesNeitherProductNorCommittedOperation() throws Exception {
        String token = admin("CONTENT_EDITOR");
        String key = key();
        String title = "Rejected workflow " + key;
        var body = write(key, null, title);
        var invalid = course(0);
        invalid.put("validityType", "DAYS");
        body.put("course", invalid);
        call("POST", ROOT, token, body, 400);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM product WHERE title=?", Long.class, title))
                .isZero();
        call("GET", ROOT + "/operations/" + key, token, null, 404);
        // A rolled-back attempt may be corrected and retried under its original key.
        var created = call("POST", ROOT, token, write(key, null, title), 200);
        assertPair(created);
        var nonzero = write(key(), null, title + " nonzero");
        nonzero.put("course", course(1));
        call("POST", ROOT, token, nonzero, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE title=?", Long.class,
                title + " nonzero")).isZero();
    }

    @Test
    void chapterCreateRetryReturnsOriginalChapterAndOriginalCourseVersion() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var created = call("POST", ROOT, token, write(key(), null, "Chapter recovery"), 200);
        String path = ROOT + "/" + courseId(created);
        String chapterKey = key();
        var request = chapter(chapterKey);
        var result = call("POST", path + "/chapters", token, request, 200);
        assertThat(result.path("chapter").path("courseId").asText()).isEqualTo(courseId(created));
        assertThat(result.path("chapter").path("id").asText()).isNotBlank();
        assertThat(result.path("workflow").path("course").path("version").asLong())
                .isGreaterThan(created.path("course").path("version").asLong());
        assertThat(call("POST", path + "/chapters", token, request, 200)).isEqualTo(result);
        assertThat(call("GET", ROOT + "/operations/" + chapterKey, token, null, 200)
                        .path("result"))
                .isEqualTo(result);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM course_chapter WHERE course_id=?", Long.class,
                        courseId(created)))
                .isEqualTo(1);
        var changed = chapter(chapterKey);
        changed.put("chapter", Map.of("version", 0, "title", "Different", "sortNo", 0,
                "durationSeconds", 60, "isTrial", false, "enabled", false));
        call("POST", path + "/chapters", token, changed, 409);
    }

    @Test
    void existingCourseReadPreservesTermsAndChaptersAndPublishNeverHalfCommits() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var p = createProduct(token, "COURSE", "Existing workflow course", 8900);
        var cover = upload(token, "PUBLIC_IMAGE", "workflow.png", "image/png", png());
        var productWrite = product("COURSE", "Existing workflow course", 8900);
        productWrite.put("coverAssetId", cover.path("id").asText());
        p = call("PATCH", "/admin/products/" + p.path("id").asText(), token, productWrite, 200);
        var legacyTerms = course(0);
        legacyTerms.put("productId", p.path("id").asText());
        var c = call("POST", "/admin/courses", token, legacyTerms, 200);
        String path = ROOT + "/" + c.path("id").asText();
        var workflow = call("GET", path, token, null, 200);
        assertPair(workflow);
        assertThat(workflow.path("course").path("teacherName").asText()).isEqualTo("Workflow teacher");
        String publishKey = key();
        call("POST", path + "/publish", token, publishRequest(publishKey, workflow), 400);
        assertThat(call("GET", path, token, null, 200)).isEqualTo(workflow);
        call("GET", ROOT + "/operations/" + publishKey, token, null, 404);

        // A real pending video cannot be published. No storage server or fake READY callback.
        var ticket = call("POST", "/admin/files/presign", token,
                Map.of("purpose", "COURSE_VIDEO", "name", "pending.mp4", "mime", "video/mp4",
                        "bytes", 128), 200);
        var chapter = call("POST", path + "/chapters", token, chapter(key()), 200).path("chapter");
        // Model the database state of an upload still in progress; the production publish
        // endpoint must inspect server-owned asset state, rather than trusting the request.
        jdbc.update("UPDATE course_chapter SET enabled=true,video_asset_id=?,version=version+1 WHERE id=?",
                ticket.path("id").asText(), chapter.path("id").asText());
        var withPending = call("GET", path, token, null, 200);
        call("POST", path + "/publish", token, publishRequest(key(), withPending), 400);
        assertThat(call("GET", "/admin/products/" + p.path("id").asText(), token, null, 200)
                        .path("status").asText())
                .isEqualTo("DRAFT");

        // Same structural development fixture used by EntitlementContentIntegrationTest;
        // obtain READY through the real upload/complete boundary, never a SQL status update.
        var video = upload(token, "COURSE_VIDEO", "workflow.mp4", "video/mp4",
                HexFormat.of().parseHex(
                        "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000"));
        String readyVideo = video.path("id").asText();
        jdbc.update("UPDATE course_chapter SET video_asset_id=?,version=version+1 WHERE id=?",
                readyVideo, chapter.path("id").asText());
        var beforePublish = call("GET", path, token, null, 200);
        jdbc.update("UPDATE file_asset SET status='DISABLED' WHERE id=?", readyVideo);
        call("POST", path + "/publish", token, publishRequest(key(), beforePublish), 400);
        assertThat(call("GET", path, token, null, 200)).isEqualTo(beforePublish);
        // Upload another accepted video rather than restoring readiness through SQL.
        var replacementVideo = upload(token, "COURSE_VIDEO", "replacement.mp4", "video/mp4",
                HexFormat.of().parseHex(
                        "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000"));
        jdbc.update("UPDATE course_chapter SET video_asset_id=?,version=version+1 WHERE id=?",
                replacementVideo.path("id").asText(), chapter.path("id").asText());
        beforePublish = call("GET", path, token, null, 200);
        var request = publishRequest(key(), beforePublish);
        var published = call("POST", path + "/publish", token, request, 200);
        assertThat(published.path("stage").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("product").path("status").asText()).isEqualTo("ON_SALE");
        assertThat(courseId(published)).isEqualTo(c.path("id").asText());
        assertThat(productId(published)).isEqualTo(p.path("id").asText());
        assertThat(published.path("chapters").get(0).path("id").asText())
                .isEqualTo(chapter.path("id").asText());
        assertThat(call("POST", path + "/publish", token, request, 200)).isEqualTo(published);
        call("PUT", path, token, write(key(), published, "Cannot change published course"), 409);
        call("POST", "/admin/products/" + productId(published) + "/unpublish", token,
                Map.of("version", published.path("product").path("version").asLong(),
                        "reason", "Continue existing course"), 200);
        var recovered = call("GET", path, token, null, 200);
        assertThat(recovered.path("stage").asText()).isEqualTo("OFF_SALE");
        assertThat(recovered.path("chapters")).isEqualTo(published.path("chapters"));
    }

    @Test
    void workflowRequiresContentEditPermission() throws Exception {
        String viewer = admin("FINANCE_OPERATOR");
        call("POST", ROOT, viewer, write(key(), null, "Forbidden"), 403);
        call("GET", ROOT + "/1", viewer, null, 403);
        call("GET", ROOT + "/operations/" + key(), viewer, null, 403);
    }

    @Test
    void readyVideoStillRequiresCoverAndStalePublishCannotChangeState() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var created = call("POST", ROOT, token, write(key(), null, "Cover requirement"), 200);
        String path = ROOT + "/" + courseId(created);
        var video = upload(token, "COURSE_VIDEO", "cover-test.mp4", "video/mp4",
                HexFormat.of().parseHex(
                        "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000"));
        var chapterBody = chapter(key());
        chapterBody.put("chapter", Map.of("version", 0, "title", "Ready chapter", "sortNo", 0,
                "durationSeconds", 60, "isTrial", true, "enabled", true,
                "videoAssetId", video.path("id").asText()));
        var withChapter = call("POST", path + "/chapters", token, chapterBody, 200).path("workflow");
        call("POST", path + "/publish", token, publishRequest(key(), withChapter), 400);
        var stale = publishRequest(key(), created);
        call("POST", path + "/publish", token, stale, 409);
        assertThat(call("GET", path, token, null, 200)).isEqualTo(withChapter);
    }

    private Map<String, Object> write(String key, JsonNode current, String title) {
        var p = product("COURSE", title, 8900);
        p.put("version", current == null ? 0L : current.path("product").path("version").asLong());
        return new LinkedHashMap<>(Map.of("operationKey", key, "product", p,
                "course", course(current == null ? 0L : current.path("course").path("version").asLong())));
    }

    private Map<String, Object> course(long version) {
        return new LinkedHashMap<>(Map.of("version", version, "validityType", "PERMANENT",
                "teacherName", "Workflow teacher", "durationMinutes", 0));
    }

    private Map<String, Object> chapter(String key) {
        return new LinkedHashMap<>(Map.of("operationKey", key, "chapter",
                Map.of("version", 0, "title", "Saved chapter", "sortNo", 0,
                        "durationSeconds", 60, "isTrial", false, "enabled", false)));
    }

    private Map<String, Object> publishRequest(String key, JsonNode current) {
        return Map.of("operationKey", key,
                "productVersion", current.path("product").path("version").asLong(),
                "courseVersion", current.path("course").path("version").asLong(),
                "reason", "Publish unified course");
    }

    private void assertPair(JsonNode workflow) {
        assertThat(courseId(workflow)).matches("[1-9][0-9]*");
        assertThat(productId(workflow)).matches("[1-9][0-9]*");
        assertThat(workflow.path("course").path("productId").asText()).isEqualTo(productId(workflow));
        assertThat(workflow.path("product").path("type").asText()).isEqualTo("COURSE");
    }

    private String courseId(JsonNode workflow) {
        return workflow.path("course").path("id").asText();
    }

    private String productId(JsonNode workflow) {
        return workflow.path("product").path("id").asText();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
