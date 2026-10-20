package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminCandidateIntegrationTest extends ContentHttpSupport {
    @Test
    void candidateQueryPaginatesAcrossKindsAndExposesCourseMapping() throws Exception {
        String token = admin("CONTENT_EDITOR");
        String key = "candidate-" + UUID.randomUUID();
        var product = createProduct(token, "COURSE", key + " course", 100);
        var course = call(
                "POST", "/admin/courses", token,
                Map.of("version", 0, "productId", product.path("id").asText(),
                        "validityType", "PERMANENT",
                        "durationMinutes", 30, "teacherName", "Teacher"), 200);
        publish(token, product);

        var result = call("GET", "/admin/content-candidates?kind=COURSE&keyword=" + key
                + "&page=1&pageSize=1", token, null, 200);

        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items")).hasSize(1);
        JsonNode item = result.path("items").get(0);
        assertThat(item.path("id").asText()).isEqualTo(course.path("id").asText());
        assertThat(item.path("productId").asText()).isEqualTo(product.path("id").asText());
        assertThat(item.path("courseId").asText()).isEqualTo(course.path("id").asText());
        assertThat(item.path("name").asText()).isEqualTo(key + " course");
        assertThat(item.path("usable").asBoolean()).isTrue();
        assertThat(item.path("availabilityReason").isNull()).isTrue();
    }

    @Test
    void selectedUnavailableIdIsReturnedWithReasonAndDefaultCandidatesStayEligible() throws Exception {
        String token = admin("CONTENT_EDITOR");
        String key = "stale-candidate-" + UUID.randomUUID();
        var draft = createProduct(token, "BOOK", key + " draft", 100);
        var live = publish(token, createProduct(token, "BOOK", key + " live", 100));

        var defaultResult = call("GET", "/admin/content-candidates?kind=PRODUCT&keyword=" + key,
                token, null, 200);
        assertThat(defaultResult.path("total").asInt()).isEqualTo(1);
        assertThat(defaultResult.path("items").get(0).path("id").asText())
                .isEqualTo(live.path("id").asText());

        var selected = call("GET", "/admin/content-candidates?kind=PRODUCT&ids="
                + draft.path("id").asText(), token, null, 200);
        assertThat(selected.path("total").asInt()).isEqualTo(1);
        assertThat(selected.path("items").get(0).path("usable").asBoolean()).isFalse();
        assertThat(selected.path("items").get(0).path("availabilityReason").asText())
                .isEqualTo("PRODUCT_NOT_ON_SALE");
    }

    @Test
    void rejectsUnknownFiltersAndRequiresContentEdit() throws Exception {
        String token = admin("ORDER_OPERATOR");
        call("GET", "/admin/content-candidates?kind=PRODUCT", token, null, 403);
        String editor = admin("CONTENT_EDITOR");
        call("GET", "/admin/content-candidates?kind=PRODUCT&status=UNKNOWN", editor, null, 400);
        call("GET", "/admin/content-candidates?kind=PRODUCT&purpose=COURSE_VIDEO", editor, null, 400);
        call("GET", "/admin/content-candidates?kind=PAPER&status=ON_SALE", editor, null, 400);
        call("GET", "/admin/content-candidates?kind=PRODUCT", null, null, 401);
    }

    @Test
    void productsIncludeAllTypesAndFilterTotalBeforeNumericPagination() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "numeric-" + UUID.randomUUID();
        var first = publish(token, createProduct(token, "BOOK", key + " first", 100));
        JsonNode last = first;
        for (int i = 0; i < 11; i++) last = publish(token, createProduct(token, "BOOK", key + " later " + i, 100));
        var courseProduct = createProduct(token, "COURSE", key + " draft course", 100);
        var result = call("GET", "/admin/content-candidates?kind=PRODUCT&keyword=" + key
                + "&pageSize=1", token, null, 200);
        assertThat(result.path("total").asInt()).isEqualTo(12);
        assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(last.path("id").asText());
        var beyond = call("GET", "/admin/content-candidates?kind=PRODUCT&keyword=" + key
                + "&type=BOOK&pageSize=1&page=13", token, null, 200);
        assertThat(beyond.path("total").asInt()).isEqualTo(12);
        assertThat(beyond.path("items")).isEmpty();
        var all = call("GET", "/admin/content-candidates?kind=PRODUCT&keyword=" + key
                + "&usable=false", token, null, 200);
        assertThat(all.path("total").asInt()).isEqualTo(13);
        assertThat(all.path("items").findValuesAsText("id")).contains(courseProduct.path("id").asText());
        jdbc.update("INSERT INTO product(id,type,title,price_fen,status,detail_json,sort_no,quantity_limit,shipping_summary,created_at,updated_at,version)"
                + " VALUES(999999999999999,'BOOK',?,100,'ON_SALE','[]',0,9,'test',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0),"
                + "(1000000000000000,'BOOK',?,100,'ON_SALE','[]',0,9,'test',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)", key + " boundary", key + " boundary");
        var boundary = call("GET", "/admin/content-candidates?kind=PRODUCT&keyword=" + key
                + "&pageSize=1", token, null, 200);
        assertThat(boundary.path("items").get(0).path("id").asText()).isEqualTo("1000000000000000");
        assertThat(boundary.path("total").asInt()).isEqualTo(14);
    }

    @Test
    void papersAndArticlesUsePublishedEligibilityAndFilteredTotals() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "publication-" + UUID.randomUUID();
        var draft = call("POST", "/admin/papers", token, Map.of("version", 0, "title", key + " draft",
                "schoolStage", "PRIMARY", "grade", "1", "subject", "MATH", "paperType", "PRACTICE",
                "status", "DRAFT", "contentBlocks", java.util.List.of(), "attachments", java.util.List.of()), 200);
        jdbc.update("UPDATE paper SET status='PUBLISHED' WHERE id=?", draft.path("id").asText());
        var papers = call("GET", "/admin/content-candidates?kind=PAPER&type=PRACTICE&keyword=" + key,
                token, null, 200);
        assertThat(papers.path("total").asInt()).isEqualTo(1);
        assertThat(papers.path("items").get(0).path("id").asText()).isEqualTo(draft.path("id").asText());
        var article = call("POST", "/admin/articles", token, Map.of("version", 0, "title", key,
                "status", "PUBLISHED", "contentBlocks", java.util.List.of()), 200);
        call("POST", "/admin/articles", token, Map.of("version", 0, "title", key + " draft",
                "status", "DRAFT", "contentBlocks", java.util.List.of()), 200);
        var articles = call("GET", "/admin/content-candidates?kind=ARTICLE&keyword=" + key, token, null, 200);
        assertThat(articles.path("total").asInt()).isEqualTo(1);
        assertThat(articles.path("items").get(0).path("id").asText()).isEqualTo(article.path("id").asText());
        var drafts = call("GET", "/admin/content-candidates?kind=ARTICLE&keyword=" + key
                + "&status=DRAFT&usable=false", token, null, 200);
        assertThat(drafts.path("total").asInt()).isEqualTo(1);
        assertThat(drafts.path("items").get(0).path("availabilityReason").asText()).isEqualTo("ARTICLE_NOT_PUBLISHED");
    }

    @Test
    void idsAreAnExclusiveLookupWithExplicitEligibilityAndMissingReasons() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var draft = createProduct(token, "BOOK", "selected draft", 100);
        publish(token, createProduct(token, "BOOK", "unrelated live", 100));
        String selectedId = draft.path("id").asText();
        var selected = call("GET", "/admin/content-candidates?kind=PRODUCT&ids=" + selectedId
                + "," + selectedId + ",9223372036854775807&usable=false", token, null, 200);
        assertThat(selected.path("total").asInt()).isEqualTo(2);
        assertThat(selected.path("items").findValuesAsText("id"))
                .containsExactly("9223372036854775807", selectedId);
        assertThat(selected.path("items").get(0).path("availabilityReason").asText())
                .isEqualTo("NOT_FOUND_OR_NOT_ACCESSIBLE");
        assertThat(selected.path("items").get(1).path("name").asText()).isEqualTo("selected draft");
        call("GET", "/admin/content-candidates?kind=PRODUCT&ids=" + selectedId + "&keyword=other",
                token, null, 400);
    }

    @Test
    void assetsRespectOwnershipPurposeAndReadyPublicThumbnailRoute() throws Exception {
        String token = admin("CONTENT_EDITOR"), other = admin("CONTENT_EDITOR");
        String key = "image-" + UUID.randomUUID();
        var ready = upload(token, "PUBLIC_IMAGE", key + ".png", "image/png", png());
        var pending = call("POST", "/admin/files/presign", token,
                Map.of("purpose", "PUBLIC_IMAGE", "name", key + "-pending.png", "mime", "image/png", "bytes", 10), 200);
        var privateAsset = call("POST", "/admin/files/presign", token,
                Map.of("purpose", "PAPER_ATTACHMENT", "name", key + ".pdf", "mime", "application/pdf", "bytes", 10), 200);
        var result = call("GET", "/admin/content-candidates?kind=ASSET&keyword=" + key, token, null, 200);
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("thumbnail").asText()).isEqualTo(ready.path("publicUrl").asText());
        var imageResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(ready.path("publicUrl").asText())).andReturn().getResponse();
        assertThat(imageResponse.getStatus()).isEqualTo(200);
        var lookup = call("GET", "/admin/content-candidates?kind=ASSET&ids="
                + privateAsset.path("id").asText() + "," + pending.path("id").asText()
                + "&usable=false", token, null, 200);
        assertThat(lookup.path("total").asInt()).isEqualTo(2);
        for (var row : lookup.path("items")) {
            assertThat(row.path("thumbnail").isNull()).isTrue();
            assertThat(row.path("usable").asBoolean()).isFalse();
        }
        var hidden = call("GET", "/admin/content-candidates?kind=ASSET&ids="
                + ready.path("id").asText() + "&usable=false", other, null, 200);
        assertThat(hidden.path("items").get(0).path("availabilityReason").asText())
                .isEqualTo("NOT_FOUND_OR_NOT_ACCESSIBLE");
        assertThat(hidden.path("items").get(0).path("name").isNull()).isTrue();
        assertThat(hidden.path("items").get(0).path("thumbnail").isNull()).isTrue();
    }

    @Test
    void keywordIsLiteralAndOversizedOrOverflowInputIsBadRequest() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "literal-" + UUID.randomUUID();
        var p = publish(token, createProduct(token, "BOOK", key + "%_'", 100));
        publish(token, createProduct(token, "BOOK", key + " other", 100));
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/admin/content-candidates").queryParam("kind", "PRODUCT")
                .queryParam("keyword", "  " + key + "%_'  ").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var result = json.readTree(response.getContentAsByteArray()).path("data");
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(p.path("id").asText());
        for (String query : java.util.List.of("keyword=" + "a".repeat(201),
                "ids=9223372036854775808", "keyword=9223372036854775808", "ids=0", "ids=1,,2",
                "ids=" + String.join(",", java.util.Collections.nCopies(51, "1")),
                "pageSize=101", "page=0")) {
            assertThat(call("GET", "/admin/content-candidates?kind=PRODUCT&" + query, token, null, 400)
                    .path("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }
}
