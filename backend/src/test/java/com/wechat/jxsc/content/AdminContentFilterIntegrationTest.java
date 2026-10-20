package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;

class AdminContentFilterIntegrationTest extends ContentHttpSupport {
    @Test
    void productsFilterBeforePaginationAndMatchTitleOrExactId() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "filter-" + UUID.randomUUID();
        var draft = createProduct(token, "BOOK", key + " draft", 100);
        var live = publish(token, createProduct(token, "BOOK", key + " live", 100));
        var off = publish(token, createProduct(token, "BOOK", key + " off", 100));
        call("POST", "/admin/products/" + off.path("id").asText() + "/unpublish", token,
                Map.of("version", off.path("version").asLong(), "reason", "test off sale"), 200);
        createProduct(token, "COURSE", key + " course", 100);
        createProduct(token, "MEMBERSHIP", key + " membership", 100);
        assertPage(token, "/admin/products?keyword=" + key + "&type=BOOK", List.of(off, live, draft));
        assertPage(token, "/admin/products?keyword=" + key + "&type=BOOK&status=DRAFT", List.of(draft));
        assertPage(token, "/admin/products?keyword=" + key + "&type=BOOK&status=ON_SALE", List.of(live));
        assertPage(token, "/admin/products?keyword=" + key + "&type=BOOK&status=OFF_SALE", List.of(off));
        assertThat(query(token, "/admin/products?keyword=" + key).path("total").asInt()).isEqualTo(5);
        var titleMatch = createProduct(token, "BOOK", "reference " + live.path("id").asText(), 100);
        var idOrTitle = query(token, "/admin/products?keyword=" + live.path("id").asText() + "&pageSize=100");
        assertThat(idOrTitle.path("items").findValuesAsText("id"))
                .contains(live.path("id").asText(), titleMatch.path("id").asText());
        assertEmpty(token, "/admin/products?keyword=R10-NO-MATCH-991&type=BOOK&status=DRAFT");
    }

    @Test
    void categoryFiltersUseRealNameTypeAndEnabledWithConsistentTotal() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "cat-" + UUID.randomUUID();
        var enabled = category(token, key + " enabled", "BOOK", true);
        var disabled = category(token, key + " disabled", "BOOK", false);
        category(token, key + " course", "COURSE", true);
        assertPage(token, "/admin/categories?keyword=" + key + "&type=BOOK", List.of(enabled, disabled));
        assertPage(token, "/admin/categories?keyword=" + key + "&type=BOOK&enabled=false", List.of(disabled));
        assertPage(token, "/admin/categories?keyword=" + key + "&enabled=true&type=BOOK", List.of(enabled));
        assertThat(query(token, "/admin/categories?keyword=" + key).path("total").asInt()).isEqualTo(3);
        assertEmpty(token, "/admin/categories?keyword=R10-NO-MATCH-991&type=BOOK");
        var publicItems = call("GET", "/categories?type=BOOK&pageSize=100", null, null, 200).path("items");
        assertThat(publicItems.findValuesAsText("id")).contains(enabled.path("id").asText())
                .doesNotContain(disabled.path("id").asText());
    }

    @Test
    void articleTitleAndStatusFiltersIncludeUnpublishedHistory() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "article-" + UUID.randomUUID();
        var draft = article(token, key + " draft", "DRAFT");
        var live = article(token, key + " live", "PUBLISHED");
        var off = article(token, key + " off", "UNPUBLISHED");
        assertPage(token, "/admin/articles?keyword=" + key, List.of(off, live, draft));
        assertPage(token, "/admin/articles?keyword=" + key + "&status=DRAFT", List.of(draft));
        assertPage(token, "/admin/articles?keyword=" + key + "&status=PUBLISHED", List.of(live));
        assertPage(token, "/admin/articles?keyword=" + key + "&status=UNPUBLISHED", List.of(off));
        assertEmpty(token, "/admin/articles?keyword=R10-NO-MATCH-991&status=DRAFT");
    }

    @Test
    void keywordsAreLiteralTrimmedAndInvalidFiltersAreRejected() throws Exception {
        String token = admin("CONTENT_EDITOR"), key = "literal-" + UUID.randomUUID();
        String special = key + "%_'";
        var p = createProduct(token, "BOOK", special, 100);
        createProduct(token, "BOOK", key + " unrelated", 100);
        var c = category(token, special, "BOOK", true);
        category(token, key + " unrelated", "BOOK", true);
        var a = article(token, special, "DRAFT");
        article(token, key + " unrelated", "DRAFT");
        assertLiteralKeyword(token, "/admin/products", "  " + special + "  ", p);
        assertLiteralKeyword(token, "/admin/categories", "  " + special + "  ", c);
        assertLiteralKeyword(token, "/admin/articles", "  " + special + "  ", a);
        for (String path : List.of("/admin/products?type=UNKNOWN", "/admin/products?status=UNKNOWN",
                "/admin/categories?type=UNKNOWN", "/admin/categories?enabled=maybe", "/admin/articles?status=UNKNOWN"))
            assertThat(call("GET", path, token, null, 400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        for (String path : List.of("/admin/products", "/admin/categories", "/admin/articles")) {
            call("GET", path + "?keyword=" + "x".repeat(201), token, null, 400);
            call("GET", path + "?pageSize=101", token, null, 400);
            call("GET", path, null, null, 401);
            call("GET", path, admin("ORDER_OPERATOR"), null, 403);
        }
    }

    private JsonNode query(String token, String path) throws Exception {
        return call("GET", path, token, null, 200);
    }
    private void assertLiteralKeyword(String token, String path, String keyword, JsonNode expected) throws Exception {
        // Supply the decoded servlet value; URL-building string overloads can encode '%' twice.
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1" + path).queryParam("keyword", keyword).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var value = json.readTree(response.getContentAsByteArray()).path("data");
        assertThat(value.path("total").asInt()).isEqualTo(1);
        assertThat(value.path("items").size()).isEqualTo(1);
        assertThat(value.path("items").get(0).path("id")).isEqualTo(expected.path("id"));
    }
    private void assertEmpty(String token, String path) throws Exception {
        var result = query(token, path);
        assertThat(result.path("total").asInt()).isZero();
        assertThat(result.path("items")).isEmpty();
    }
    private void assertPage(String token, String path, List<JsonNode> expected) throws Exception {
        for (int i = 0; i < expected.size(); i++) {
            var result = query(token, path + "&page=" + (i + 1) + "&pageSize=1");
            assertThat(result.path("total").asInt()).isEqualTo(expected.size());
            assertThat(result.path("items").size()).isEqualTo(1);
            assertThat(result.path("items").get(0).path("id")).isEqualTo(expected.get(i).path("id"));
        }
        var beyond = query(token, path + "&page=" + (expected.size() + 1) + "&pageSize=1");
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("total").asInt()).isEqualTo(expected.size());
    }
    private JsonNode category(String token, String name, String type, boolean enabled) throws Exception {
        return call("POST", "/admin/categories", token,
                Map.of("version", 0, "name", name, "type", type, "enabled", enabled, "sortNo", 0), 200);
    }
    private JsonNode article(String token, String title, String status) throws Exception {
        return call("POST", "/admin/articles", token,
                Map.of("version", 0, "title", title, "status", status, "contentBlocks", List.of()), 200);
    }
}
