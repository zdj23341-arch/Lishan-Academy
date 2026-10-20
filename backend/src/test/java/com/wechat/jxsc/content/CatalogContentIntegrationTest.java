package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.*;

class CatalogContentIntegrationTest extends ContentHttpSupport {
    @Test
    void publicCatalogFiltersTypedDetailsAndUnpublishRetainsMetadata() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var category =
                call(
                        "POST",
                        "/admin/categories",
                        token,
                        Map.of(
                                "version",
                                0,
                                "name",
                                "DEV Writing",
                                "type",
                                "BOOK",
                                "sortNo",
                                1,
                                "enabled",
                                true),
                        200);
        var first = product("BOOK", "DEV Observable Writing", 1234);
        first.put("categoryIds", List.of(category.path("id").asText()));
        var draft = call("POST", "/admin/products", token, first, 200);
        String id = draft.path("id").asText();
        call("GET", "/products/" + id, null, null, 404);
        var live = publish(token, draft);
        var second = publish(token, createProduct(token, "BOOK", "DEV Observable Practice", 2234));
        var page =
                call(
                        "GET",
                        "/products?type=BOOK&keyword=Observable&sort=PRICE_DESC&page=1&pageSize=1",
                        null,
                        null,
                        200);
        assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("id")).isEqualTo(second.path("id"));
        var filtered =
                call(
                        "GET",
                        "/products?categoryId=" + category.path("id").asText(),
                        null,
                        null,
                        200);
        assertThat(filtered.path("items").toString())
                .contains(id)
                .doesNotContain("DEV Observable Practice");
        jdbc.update("UPDATE book_inventory SET on_hand=11,reserved=3 WHERE product_id=?", id);
        var detail = call("GET", "/products/" + id, null, null, 200);
        assertThat(detail.path("book").path("availableStock").asInt()).isEqualTo(8);
        assertThat(detail.path("book").path("quantityLimit").asInt()).isEqualTo(9);
        assertThat(detail.path("allowedActions").toString()).contains("BUY");
        assertThat(detail.has("course")).isFalse();
        assertThat(detail.has("membership")).isFalse();
        call(
                "POST",
                "/admin/products/" + id + "/unpublish",
                token,
                Map.of("version", live.path("version").asLong(), "reason", "Season ended"),
                200);
        assertThat(call("GET", "/products/" + id, null, null, 200).path("allowedActions"))
                .isEmpty();
        assertThat(
                        call(
                                        "GET",
                                        "/products?categoryId=" + category.path("id").asText(),
                                        null,
                                        null,
                                        200)
                                .path("items"))
                .isEmpty();
        call("DELETE", "/admin/products/" + id, token, null, 405);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM inventory_ledger WHERE product_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        call("GET", "/products?pageSize=101", null, null, 400);
        call("GET", "/products?sort=UNKNOWN", null, null, 400);
        call("GET", "/products?type=BOOK&type=COURSE", null, null, 400);
        assertThat(
                        call("GET", "/config/public", null, null, 200)
                                .path("allowedFileFormats")
                                .toString())
                .contains("PDF", "DOCX");
    }

    @Test
    void adminPermissionsVersionsCategoryTypesAndPublicBodyAssetsAreEnforced() throws Exception {
        String token = admin("CONTENT_EDITOR");
        call("POST", "/admin/products", buyer("buyer"), product("BOOK", "Forbidden", 1), 403);
        call("GET", "/admin/products", admin("ORDER_OPERATOR"), null, 403);
        call("POST", "/admin/products", token, product("BOOK", "Negative", -1), 400);
        var c =
                call(
                        "POST",
                        "/admin/categories",
                        token,
                        Map.of(
                                "version",
                                0,
                                "name",
                                "DEV Course category",
                                "type",
                                "COURSE",
                                "sortNo",
                                0,
                                "enabled",
                                true),
                        200);
        var bad = product("BOOK", "Wrong category", 1);
        bad.put("categoryIds", List.of(c.path("id").asText()));
        call("POST", "/admin/products", token, bad, 400);
        var p = createProduct(token, "BOOK", "Versioned", 12);
        var edit = product("BOOK", "Changed", 99);
        call("PATCH", "/admin/products/" + p.path("id").asText(), token, edit, 200);
        call("PATCH", "/admin/products/" + p.path("id").asText(), token, edit, 409);
        var privateFile = upload(token, "PAPER_ATTACHMENT", "private.png", "image/png", png());
        var b = product("BOOK", "Private image", 1);
        b.put(
                "contentBlocks",
                List.of(Map.of("type", "IMAGE", "assetId", privateFile.path("id").asText())));
        call("POST", "/admin/products", token, b, 400);
        b.put("contentBlocks", List.of(Map.of("type", "HTML", "text", "<script>bad</script>")));
        call("POST", "/admin/products", token, b, 400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM operation_audit_log WHERE"
                                        + " resource_type='PRODUCT' AND resource_id=?",
                                Integer.class,
                                p.path("id").asText()))
                .isEqualTo(2);
    }

    @Test
    void coursesMembershipPapersAndArticlesExposeOnlyPublishedMetadata() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var p = createProduct(token, "COURSE", "DEV Lesson", 100);
        var course =
                call(
                        "POST",
                        "/admin/courses",
                        token,
                        Map.of(
                                "version",
                                0,
                                "productId",
                                p.path("id").asText(),
                                "validityType",
                                "DAYS",
                                "validityDays",
                                30,
                                "teacherName",
                                "DEV Teacher"),
                        200);
        String courseId = course.path("id").asText();
        var ch1 =
                call(
                        "POST",
                        "/admin/courses/" + courseId + "/chapters",
                        token,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "First",
                                "sortNo",
                                3,
                                "durationSeconds",
                                60,
                                "isTrial",
                                true,
                                "enabled",
                                true),
                        200);
        var ch2 =
                call(
                        "POST",
                        "/admin/courses/" + courseId + "/chapters",
                        token,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "Second",
                                "sortNo",
                                1,
                                "durationSeconds",
                                120,
                                "isTrial",
                                false,
                                "enabled",
                                true),
                        200);
        var current = call("GET", "/admin/courses/" + courseId, token, null, 200);
        var reorder =
                Map.of(
                        "version",
                        current.path("version").asLong(),
                        "chapters",
                        List.of(ch1.path("id").asText(), ch2.path("id").asText()));
        call("PUT", "/admin/courses/" + courseId + "/chapter-order", token, reorder, 200);
        call("PUT", "/admin/courses/" + courseId + "/chapter-order", token, reorder, 409);
        publish(token, p);
        var chapters = call("GET", "/courses/" + courseId + "/chapters", null, null, 200);
        assertThat(chapters.path("items").get(0).path("title").asText()).isEqualTo("First");
        assertThat(chapters.path("items").get(0).path("canPlay").asBoolean()).isFalse();
        assertThat(chapters.toString()).doesNotContain("videoAssetId", "storageKey", "url");
        var membership = publish(token, createProduct(token, "MEMBERSHIP", "DEV 6 month", 600));
        assertThat(
                        call("GET", "/products/" + membership.path("id").asText(), null, null, 200)
                                .path("membership")
                                .path("durationMonths")
                                .asInt())
                .isEqualTo(6);
        assertThat(call("GET", "/membership/plans", null, null, 200).path("items").toString())
                .contains("600");
        var file = upload(token, "PAPER_ATTACHMENT", "authored.png", "image/png", png());
        var paper =
                call(
                        "POST",
                        "/admin/papers",
                        token,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV Chinese Paper",
                                "stage",
                                "JUNIOR",
                                "grade",
                                "9",
                                "subject",
                                "CHINESE",
                                "year",
                                2026,
                                "status",
                                "PUBLISHED",
                                "contentBlocks",
                                List.of(),
                                "attachments",
                                List.of(
                                        Map.of(
                                                "assetId",
                                                file.path("id").asText(),
                                                "title",
                                                "Authored sheet",
                                                "sortNo",
                                                1))),
                        200);
        var publicPaper = call("GET", "/papers/" + paper.path("id").asText(), null, null, 200);
        assertThat(publicPaper.path("attachments").get(0).path("canDownload").asBoolean())
                .isFalse();
        assertThat(publicPaper.toString()).doesNotContain("storageKey", "uploadToken", "url");
        assertThat(
                        call(
                                        "GET",
                                        "/papers?grade=9&year=2026&subject=CHINESE&keyword=Chinese",
                                        null,
                                        null,
                                        200)
                                .path("total")
                                .asInt())
                .isEqualTo(1);
        assertThat(call("GET", "/papers?year=2025", null, null, 200).path("items")).isEmpty();
        var article =
                call(
                        "POST",
                        "/admin/articles",
                        token,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV Article",
                                "status",
                                "DRAFT",
                                "contentBlocks",
                                List.of(Map.of("type", "TEXT", "text", "Authored public text"))),
                        200);
        call("GET", "/articles/" + article.path("id").asText(), null, null, 404);
        call(
                "PATCH",
                "/admin/articles/" + article.path("id").asText(),
                token,
                Map.of(
                        "version",
                        0,
                        "title",
                        "DEV Article",
                        "status",
                        "PUBLISHED",
                        "contentBlocks",
                        List.of(Map.of("type", "TEXT", "text", "Authored public text"))),
                200);
        assertThat(
                        call("GET", "/articles/" + article.path("id").asText(), null, null, 200)
                                .path("contentBlocks")
                                .toString())
                .contains("Authored public text");
    }
}
