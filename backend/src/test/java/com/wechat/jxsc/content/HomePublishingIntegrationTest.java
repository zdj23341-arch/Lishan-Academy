package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.*;

class HomePublishingIntegrationTest extends ContentHttpSupport {
    @Test
    void invalidTargetsCannotPublishAndConcurrentPublishHasOneWinner() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var asset = upload(token, "PUBLIC_IMAGE", "race-home.png", "image/png", png());
        var target = Map.of("type", "ARTICLE", "id", "999999999", "filters", Map.of());
        List<Map<String, Object>> banners = new ArrayList<>(), shortcuts = new ArrayList<>();
        for (int i = 1; i <= 3; i++)
            banners.add(
                    Map.of(
                            "id",
                            "" + i,
                            "title",
                            "DEV " + i,
                            "assetId",
                            asset.path("id").asText(),
                            "sortNo",
                            i,
                            "enabled",
                            true,
                            "target",
                            target));
        var safe = Map.of("type", "PAPER_LIST", "filters", Map.of());
        for (int i = 1; i <= 8; i++)
            shortcuts.add(
                    Map.of(
                            "id",
                            "" + i,
                            "label",
                            "DEV " + i,
                            "assetId",
                            asset.path("id").asText(),
                            "sortNo",
                            i,
                            "enabled",
                            true,
                            "target",
                            safe));
        var draft = call("GET", "/admin/home/draft", token, null, 200);
        var body = new LinkedHashMap<String, Object>();
        body.put("revision", draft.path("revision").asLong());
        body.put("banners", banners);
        body.put("shortcuts", shortcuts);
        body.put("sections", List.of());
        var saved = call("PUT", "/admin/home/draft", token, body, 200);
        call(
                "POST",
                "/admin/home/publish",
                token,
                Map.of("revision", saved.path("revision").asLong(), "reason", "Invalid target"),
                400);
        var article =
                call(
                        "POST",
                        "/admin/articles",
                        token,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV target",
                                "status",
                                "PUBLISHED",
                                "contentBlocks",
                                List.of()),
                        200);
        for (int i = 0; i < banners.size(); i++) {
            var b = new LinkedHashMap<>(banners.get(i));
            b.put(
                    "target",
                    Map.of(
                            "type",
                            "ARTICLE",
                            "id",
                            article.path("id").asText(),
                            "filters",
                            Map.of()));
            banners.set(i, b);
        }
        body.put("revision", saved.path("revision").asLong());
        var ready = call("PUT", "/admin/home/draft", token, body, 200);
        byte[] publish =
                json.writeValueAsBytes(
                        Map.of(
                                "revision",
                                ready.path("revision").asLong(),
                                "reason",
                                "Concurrent publish"));
        var start = new java.util.concurrent.CountDownLatch(1);
        var statuses = new ArrayList<Integer>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    org.springframework.test.web.servlet.request
                                                            .MockMvcRequestBuilders.post(
                                                                    "/api/v1/admin/home/publish")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + token)
                                                            .contentType("application/json")
                                                            .content(publish))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            for (var f : futures) statuses.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        jdbc.update(
                                                "UPDATE home_layout SET revision=10000 WHERE"
                                                        + " revision=?",
                                                ready.path("revision").asLong() + 1)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void publishingUsesCasValidatedRelationsAndImmutableSnapshots() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var asset = upload(token, "PUBLIC_IMAGE", "home.png", "image/png", png());
        var product = publish(token, createProduct(token, "BOOK", "Published book", 500));
        var start = call("GET", "/home", null, null, 200);
        var draft = call("GET", "/admin/home/draft", token, null, 200);
        var target =
                Map.of("type", "PRODUCT", "id", product.path("id").asText(), "filters", Map.of());
        List<Map<String, Object>> banners = new ArrayList<>(), shortcuts = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            banners.add(
                    Map.of(
                            "id",
                            "" + (i + 1),
                            "title",
                            "Banner " + i,
                            "assetId",
                            asset.path("id").asText(),
                            "sortNo",
                            3 - i,
                            "enabled",
                            true,
                            "target",
                            target));
        for (int i = 0; i < 8; i++)
            shortcuts.add(
                    Map.of(
                            "id",
                            "" + (i + 1),
                            "label",
                            "Entry " + i,
                            "assetId",
                            asset.path("id").asText(),
                            "sortNo",
                            i,
                            "enabled",
                            true,
                            "target",
                            target));
        var body = new LinkedHashMap<String, Object>();
        body.put("revision", draft.path("revision").asLong());
        body.put("banners", banners);
        body.put("shortcuts", shortcuts);
        body.put(
                "sections",
                List.of(
                        Map.of(
                                "id",
                                "1",
                                "type",
                                "PRODUCT_GRID",
                                "title",
                                "Featured",
                                "sortNo",
                                0,
                                "enabled",
                                true,
                                "productIds",
                                List.of(product.path("id").asText()))));
        var saved = call("PUT", "/admin/home/draft", token, body, 200);
        assertThat(call("GET", "/home", null, null, 200)).isEqualTo(start);
        call("PUT", "/admin/home/draft", token, body, 409);
        var published =
                call(
                        "POST",
                        "/admin/home/publish",
                        token,
                        Map.of(
                                "revision",
                                saved.path("revision").asLong(),
                                "reason",
                                "Launch home"),
                        200);
        assertThat(published.path("banners").size()).isEqualTo(3);
        assertThat(published.path("shortcuts").size()).isEqualTo(8);
        assertThat(published.path("banners").get(0).path("title").asText()).isEqualTo("Banner 2");
        call(
                "POST",
                "/admin/home/publish",
                token,
                Map.of("revision", saved.path("revision").asLong(), "reason", "Stale"),
                409);
        var latest = call("GET", "/admin/home/draft", token, null, 200);
        body.put("revision", latest.path("revision").asLong());
        body.put("banners", List.of());
        var incomplete = call("PUT", "/admin/home/draft", token, body, 200);
        call(
                "POST",
                "/admin/home/publish",
                token,
                Map.of("revision", incomplete.path("revision").asLong(), "reason", "Invalid"),
                400);
        assertThat(call("GET", "/home", null, null, 200)).isEqualTo(published);
        String snapshot =
                jdbc.queryForObject(
                        "SELECT modules_json FROM home_layout WHERE status='PUBLISHED' ORDER BY"
                                + " revision DESC LIMIT 1",
                        String.class);
        call(
                "POST",
                "/admin/products/" + product.path("id").asText() + "/unpublish",
                token,
                Map.of("version", product.path("version").asLong(), "reason", "Unavailable"),
                200);
        assertThat(call("GET", "/home", null, null, 200).path("sections").get(0).path("products"))
                .isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT modules_json FROM home_layout WHERE status='PUBLISHED'"
                                        + " ORDER BY revision DESC LIMIT 1",
                                String.class))
                .isEqualTo(snapshot);
    }
}
