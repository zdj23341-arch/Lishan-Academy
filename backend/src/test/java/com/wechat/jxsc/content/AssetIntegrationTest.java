package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;

import java.util.*;

class AssetIntegrationTest extends ContentHttpSupport {
    @Test
    void bytesAreVerifiedBeforeReadinessAndCredentialsCannotOverwriteReadyFiles() throws Exception {
        String token = admin("CONTENT_EDITOR");
        byte[] bytes = png();
        var ticket =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "name",
                                "../../original.png",
                                "mime",
                                "image/png",
                                "bytes",
                                bytes.length,
                                "purpose",
                                "PUBLIC_IMAGE"),
                        200);
        String id = ticket.path("id").asText();
        call("POST", "/admin/files/" + id + "/complete", token, Map.of(), 409);
        assertThat(
                        mvc.perform(get("/api/v1/assets/" + id + "/content"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(409);
        putBytes(token, ticket, bytes, 200);
        var ready = call("POST", "/admin/files/" + id + "/complete", token, Map.of(), 200);
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.path("bytes").asInt()).isEqualTo(bytes.length);
        assertThat(ready.path("sha256").asText())
                .isEqualTo(
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(bytes)));
        var response =
                mvc.perform(get("/api/v1/assets/" + id + "/content")).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getHeader("Cache-Control").split(", "))
                .containsExactlyInAnyOrder("max-age=300", "private", "must-revalidate");
        putBytes(token, ticket, png(), 409);
        assertThat(
                        call("POST", "/admin/files/" + id + "/complete", token, Map.of(), 200)
                                .path("sha256"))
                .isEqualTo(ready.path("sha256"));
        String key =
                jdbc.queryForObject(
                        "SELECT storage_key FROM file_asset WHERE id=?", String.class, id);
        assertThat(key).doesNotContain("..", "original", "/", "\\");
    }

    @Test
    void invalidFormatsSizesAndOwnerPurposeConfusionAreRejected() throws Exception {
        String token = admin("CONTENT_EDITOR");
        call(
                "POST",
                "/admin/files/presign",
                token,
                Map.of(
                        "name",
                        "evil.exe",
                        "mime",
                        "image/png",
                        "bytes",
                        10,
                        "purpose",
                        "PUBLIC_IMAGE"),
                400);
        call(
                "POST",
                "/admin/files/presign",
                token,
                Map.of(
                        "name",
                        "huge.png",
                        "mime",
                        "image/png",
                        "bytes",
                        10485761,
                        "purpose",
                        "PUBLIC_IMAGE"),
                400);
        var invalid =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "name",
                                "bad.png",
                                "mime",
                                "image/png",
                                "bytes",
                                4,
                                "purpose",
                                "PUBLIC_IMAGE"),
                        200);
        putBytes(token, invalid, new byte[] {1, 2, 3, 4}, 200);
        call(
                "POST",
                "/admin/files/" + invalid.path("id").asText() + "/complete",
                token,
                Map.of(),
                422);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM file_asset WHERE id=?",
                                String.class,
                                invalid.path("id").asText()))
                .isEqualTo("REJECTED");
        String user = buyer("buyer"), other = buyer("buyer-expired-member");
        call(
                "POST",
                "/files/presign",
                user,
                Map.of("name", "x.png", "mime", "image/png", "bytes", 4, "purpose", "PUBLIC_IMAGE"),
                403);
        var own =
                call(
                        "POST",
                        "/files/presign",
                        user,
                        Map.of(
                                "name",
                                "evidence.png",
                                "mime",
                                "image/png",
                                "bytes",
                                png().length,
                                "purpose",
                                "AFTERSALE_EVIDENCE"),
                        200);
        putBytes(other, own, png(), 404);
        call("POST", "/files/" + own.path("id").asText() + "/complete", other, Map.of(), 404);
        putBytes(user, own, png(), 200);
        call("POST", "/files/" + own.path("id").asText() + "/complete", user, Map.of(), 200);
        call("GET", "/assets/" + own.path("id").asText() + "/content", null, null, 404);
        var tooLong =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "name",
                                "bounded.png",
                                "mime",
                                "image/png",
                                "bytes",
                                4,
                                "purpose",
                                "PUBLIC_IMAGE"),
                        200);
        putBytes(token, tooLong, png(), 400);
        call(
                "POST",
                "/admin/files/" + tooLong.path("id").asText() + "/complete",
                token,
                Map.of(),
                409);
    }
}
