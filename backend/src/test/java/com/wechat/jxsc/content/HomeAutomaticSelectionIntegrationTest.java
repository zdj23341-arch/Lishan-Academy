package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class HomeAutomaticSelectionIntegrationTest extends ContentHttpSupport {
    @Test
    void firstPublicationTimeSurvivesEditsAndRepeatedPublishing() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var item = publish(token, createProduct(token, "BOOK", "首次发布时间", 100));
        var id = item.path("id").asText();
        var first = jdbc.queryForObject("SELECT first_published_at FROM product WHERE id=?",
                java.sql.Timestamp.class, id);
        assertThat(first).isNotNull();
        var body = product("BOOK", "普通编辑不能改变发布时间", 200);
        body.put("version", item.path("version").asLong());
        var edited = call("PATCH", "/admin/products/" + id, token, body, 200);
        publish(token, edited);
        assertThat(jdbc.queryForObject("SELECT first_published_at FROM product WHERE id=?",
                java.sql.Timestamp.class, id)).isEqualTo(first);
    }

    @Test
    void manualModeKeepsSelectedOrderAndDoesNotFillWithNewContent() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var selected = publish(token, createProduct(token, "BOOK", "手动精选", 100));
        var draft = json.convertValue(home(token, List.of()), Map.class);
        var section = (Map<String, Object>) ((List<?>) draft.get("sections")).get(0);
        section.put("selectionMode", "MANUAL");
        section.put("productIds", List.of(selected.path("id").asText()));
        var saved = call("PUT", "/admin/home/draft", token, draft, 200);
        call("POST", "/admin/home/publish", token,
                Map.of("revision", saved.path("revision").asLong(), "reason", "手动精选"), 200);
        publish(token, createProduct(token, "BOOK", "不自动加入", 200));
        assertThat(ids(call("GET", "/home", null, null, 200))).containsExactly(selected.path("id").asText());
    }

    @Test
    void publishedAutomaticRulesRefreshWithoutPublishingDraftChanges() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var first = publish(token, createProduct(token, "BOOK", "自动推荐甲", 100));
        var second = publish(token, createProduct(token, "BOOK", "自动推荐乙", 200));
        var draft = home(token, List.of(first.path("id").asText()));
        var section = draft.withArray("sections").get(0);
        assertThat(section.path("selectionMode").asText()).isEqualTo("AUTO");
        var published = call("POST", "/admin/home/publish", token,
                Map.of("revision", draft.path("revision").asLong(), "reason", "自动推荐"), 200);
        long revision = published.path("revision").asLong();
        assertThat(ids(published)).containsExactly(first.path("id").asText(), second.path("id").asText());
        var third = publish(token, createProduct(token, "BOOK", "自动推荐丙", 300));
        var current = call("GET", "/home", null, null, 200);
        assertThat(ids(current)).containsExactly(first.path("id").asText(), third.path("id").asText());
        assertThat(current.path("revision").asLong()).isEqualTo(revision);
        var saved = json.convertValue(call("GET", "/admin/home/draft", token, null, 200), Map.class);
        ((Map<String, Object>) ((List<?>) saved.get("sections")).get(0)).put("selectionMode", "MANUAL");
        ((Map<String, Object>) ((List<?>) saved.get("sections")).get(0)).put("pinnedIds", List.of());
        call("PUT", "/admin/home/draft", token, saved, 200);
        assertThat(ids(call("GET", "/home", null, null, 200)))
                .containsExactly(first.path("id").asText(), third.path("id").asText());
        call("POST", "/admin/products/" + first.path("id").asText() + "/unpublish", token,
                Map.of("version", first.path("version").asLong(), "reason", "下架"), 200);
        assertThat(ids(call("GET", "/home", null, null, 200)))
                .containsExactly(third.path("id").asText(), second.path("id").asText());
    }

    @Test
    void recommendationOnlyUpdatesDraftAndRejectsStaleRevision() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var item = publish(token, createProduct(token, "BOOK", "推荐草稿", 100));
        var draft = home(token, List.of());
        var body = Map.of("revision", draft.path("revision").asLong(),
                "contentId", item.path("id").asText(), "remove", false);
        var saved = call("POST", "/admin/home/sections/books/recommend", token, body, 200);
        assertThat(saved.path("sections").get(0).path("pinnedIds").get(0).asText())
                .isEqualTo(item.path("id").asText());
        call("POST", "/admin/home/sections/books/recommend", token, body, 409);
        String other = admin("CONTENT_EDITOR");
        call("POST", "/admin/home/sections/books/recommend", other,
                Map.of("revision", saved.path("revision").asLong(), "contentId", item.path("id").asText(), "remove", true), 200);
    }

    @Test
    void paperAutoSelectionUsesPublishedPaperIdsAndNeverDownloadsOriginals() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var attachment = upload(token, "PAPER_ATTACHMENT", "题目.png", "image/png", png());
        var paper = call("POST", "/admin/papers", token, Map.of("version", 0,
                "title", "自动试卷", "schoolStage", "JUNIOR", "grade", "7", "subject", "语文",
                "status", "PUBLISHED", "contentBlocks", List.of(), "attachments",
                List.of(Map.of("assetId", attachment.path("id").asText(), "title", "附件", "sortNo", 0))), 200);
        var first = jdbc.queryForObject("SELECT first_published_at FROM paper WHERE id=?",
                java.time.LocalDateTime.class, paper.path("id").asText());
        assertThat(first).isNotNull();
        jdbc.update("UPDATE paper SET title='普通编辑' WHERE id=?", paper.path("id").asText());
        jdbc.update("UPDATE paper SET status='UNPUBLISHED' WHERE id=?", paper.path("id").asText());
        jdbc.update("UPDATE paper SET status='PUBLISHED' WHERE id=?", paper.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT first_published_at FROM paper WHERE id=?",
                java.time.LocalDateTime.class, paper.path("id").asText())).isEqualTo(first);
        var draft = json.convertValue(home(token, List.of()), Map.class);
        var section = (Map<String, Object>) ((List<?>) draft.get("sections")).get(0);
        section.put("type", "PAPER_GRID");
        section.put("pinnedIds", List.of(paper.path("id").asText()));
        var saved = call("PUT", "/admin/home/draft", token, draft, 200);
        var view = call("POST", "/admin/home/publish", token,
                Map.of("revision", saved.path("revision").asLong(), "reason", "试卷自动展示"), 200);
        var summary = view.path("sections").get(0).path("papers").get(0);
        assertThat(summary.path("id").asText()).isEqualTo(paper.path("id").asText());
        assertThat(summary.path("accessCondition").asText()).isEqualTo("ACTIVE_MEMBER");
        assertThat(summary.has("attachments")).isFalse();
        assertThat(view.path("sections").get(0).path("products")).isEmpty();
    }

    @Test
    void invalidModesLimitsAndMismatchedPinnedTypesCannotSave() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var draft = json.convertValue(home(token, List.of()), Map.class);
        var section = (Map<String, Object>) ((List<?>) draft.get("sections")).get(0);
        section.put("type", "PRODUCT_GRID");
        call("PUT", "/admin/home/draft", token, draft, 400);
        section.put("type", "BOOK_GRID");
        section.put("displayLimit", 0);
        call("PUT", "/admin/home/draft", token, draft, 400);
        section.put("displayLimit", 51);
        call("PUT", "/admin/home/draft", token, draft, 400);
        section.put("displayLimit", 4);
        section.put("pinnedIds", Arrays.asList((String) null));
        call("PUT", "/admin/home/draft", token, draft, 400);
        var courseProduct = createProduct(token, "COURSE", "不能放图书栏目的课程", 100);
        section.put("pinnedIds", List.of(courseProduct.path("id").asText()));
        call("PUT", "/admin/home/draft", token, draft, 400);
    }

    private JsonNode home(String token, List<String> pinned) throws Exception {
        var asset = upload(token, "PUBLIC_IMAGE", "automatic.png", "image/png", png());
        var target = Map.of("type", "PAPER_LIST", "filters", Map.of());
        var banners = new ArrayList<Map<String, Object>>();
        var shortcuts = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) banners.add(Map.of("id", "b"+i, "title", "横幅", "assetId", asset.path("id").asText(), "sortNo", i, "enabled", true, "target", target));
        for (int i = 0; i < 6; i++) shortcuts.add(Map.of("id", "s"+i, "label", "入口", "assetId", asset.path("id").asText(), "sortNo", i, "enabled", true, "target", target));
        return call("PUT", "/admin/home/draft", token, Map.of(
                "revision", call("GET", "/admin/home/draft", token, null, 200).path("revision").asLong(),
                "banners", banners, "shortcuts", shortcuts,
                "sections", List.of(Map.of("id", "books", "title", "图书", "type", "BOOK_GRID",
                        "sortNo", 0, "enabled", true, "productIds", List.of(), "paperIds", List.of(),
                        "selectionMode", "AUTO", "displayLimit", 2, "pinnedIds", pinned))), 200);
    }

    private List<String> ids(JsonNode home) {
        var result = new ArrayList<String>();
        home.path("sections").get(0).path("products").forEach(p -> result.add(p.path("id").asText()));
        return result;
    }
}
