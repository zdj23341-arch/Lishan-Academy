package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class ContentBoundaryIntegrationTest extends ContentHttpSupport {
    @Test
    void concurrentEditsHaveExactlyOneWinnerAndKeepRequiredNullableDtoFields() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var p = createProduct(token, "BOOK", "Concurrent original", 100);
        var start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (String title : List.of("Editor one", "Editor two"))
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    patch(
                                                                    "/api/v1/admin/products/"
                                                                            + p.path("id").asText())
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + token)
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsBytes(
                                                                            product(
                                                                                    "BOOK", title,
                                                                                    200))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            List<Integer> status = new ArrayList<>();
            for (var f : futures) status.add(f.get(10, TimeUnit.SECONDS));
            assertThat(status).containsExactlyInAnyOrder(200, 409);
        }
        var current = call("GET", "/admin/products/" + p.path("id").asText(), token, null, 200);
        publish(token, current);
        var detail = call("GET", "/products/" + p.path("id").asText(), null, null, 200);
        assertThat(detail.has("coverUrl")).isTrue();
        assertThat(detail.get("coverUrl").isNull()).isTrue();
        assertThat(detail.has("originalPriceFen")).isTrue();
        assertThat(detail.has("subtitle")).isTrue();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM inventory_ledger WHERE product_id=?",
                                        p.path("id").asText()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void truncatedContainersAreRejectedAndRevokedEditorsCannotUseUploadTickets() throws Exception {
        String token = admin("CONTENT_EDITOR");
        byte[] webp = {'R', 'I', 'F', 'F', 8, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        byte[] mp4 = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        for (var sample : List.of(Map.entry("WEBP", webp), Map.entry("MP4", mp4))) {
            boolean video = sample.getKey().equals("MP4");
            var ticket =
                    call(
                            "POST",
                            "/admin/files/presign",
                            token,
                            Map.of(
                                    "purpose",
                                    video ? "COURSE_VIDEO" : "PUBLIC_IMAGE",
                                    "name",
                                    "truncated." + sample.getKey().toLowerCase(),
                                    "mime",
                                    video ? "video/mp4" : "image/webp",
                                    "bytes",
                                    sample.getValue().length),
                            200);
            putBytes(token, ticket, sample.getValue(), 200);
            call(
                    "POST",
                    "/admin/files/" + ticket.path("id").asText() + "/complete",
                    token,
                    Map.of(),
                    422);
        }
        var ticket =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "purpose",
                                "PUBLIC_IMAGE",
                                "name",
                                "x.png",
                                "mime",
                                "image/png",
                                "bytes",
                                png().length),
                        200);
        var me = call("GET", "/admin/me", token, null, 200);
        jdbc.update("DELETE FROM admin_user_role WHERE admin_user_id=?", me.path("id").asText());
        putBytes(token, ticket, png(), 403);
    }
}
