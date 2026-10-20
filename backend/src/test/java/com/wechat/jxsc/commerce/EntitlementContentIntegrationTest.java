package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.content.DevelopmentFileFixtures;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class EntitlementContentIntegrationTest extends EntitlementTestSupport {
    JsonNode video() throws Exception {
        byte[] bytes =
                HexFormat.of()
                        .parseHex(
                                "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000");
        return upload(a, "COURSE_VIDEO", "DEV.mp4", "video/mp4", bytes);
    }

    JsonNode chapter(JsonNode c, boolean trial, String asset) throws Exception {
        return call(
                "POST",
                "/admin/courses/" + c.path("id").asText() + "/chapters",
                a,
                Map.of(
                        "version",
                        0,
                        "title",
                        "DEV Chapter",
                        "sortNo",
                        0,
                        "durationSeconds",
                        120,
                        "isTrial",
                        trial,
                        "enabled",
                        true,
                        "videoAssetId",
                        asset),
                200);
    }

    String play(JsonNode c, JsonNode ch) {
        return "/courses/"
                + c.path("id").asText()
                + "/chapters/"
                + ch.path("id").asText()
                + "/play-url";
    }

    String paper(List<JsonNode> files) throws Exception {
        return call(
                        "POST",
                        "/admin/papers",
                        a,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV Paper",
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
                                files.stream()
                                        .map(
                                                f ->
                                                        Map.of(
                                                                "assetId",
                                                                f.path("id").asText(),
                                                                "title",
                                                                "DEV file",
                                                                "sortNo",
                                                                0))
                                        .toList()),
                        200)
                .path("id")
                .asText();
    }

    @Test
    void trialIsChapterSpecificAndLearningRequiresOwnedPaidCourse() throws Exception {
        var c = course("DAYS", 30);
        var v = video();
        var trial = chapter(c, true, v.path("id").asText());
        var paid = chapter(c, false, v.path("id").asText());
        var ticket = call("GET", play(c, trial), null, null, 200);
        assertThat(ticket.path("expiresAt").asText()).isEqualTo("2026-10-10T02:05:00Z");
        call("GET", play(c, paid), b, null, 403);
        var rows =
                call("GET", "/courses/" + c.path("id").asText() + "/chapters", null, null, 200)
                        .path("items");
        assertThat(rows.get(0).path("canPlay").asBoolean()).isTrue();
        assertThat(rows.get(1).path("canPlay").asBoolean()).isFalse();
        String progress = "/learning/chapters/" + paid.path("id").asText() + "/progress";
        call("PUT", progress, b, Map.of("positionSeconds", 40), 403);
        deliver(paid(member(6), "2026-10-10T02:00:00Z"));
        call("PUT", progress, b, Map.of("positionSeconds", 40), 403);
        String order = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        deliver(order);
        // A player reporting past the declared duration is clamped, not rejected.
        var clamped = call("PUT", progress, b, Map.of("positionSeconds", 121), 200);
        assertThat(clamped.path("positionSeconds").asInt()).isEqualTo(120);
        assertThat(clamped.path("completed").asBoolean()).isTrue();
        var saved = call("PUT", progress, b, Map.of("positionSeconds", 120), 200);
        assertThat(saved.path("completed").asBoolean()).isTrue();
        assertThat(call("PUT", progress, b, Map.of("positionSeconds", 120), 200)).isEqualTo(saved);
        assertThat(
                        call("GET", "/learning/courses", b, null, 200)
                                .path("items")
                                .get(0)
                                .path("courseId")
                                .asText())
                .isEqualTo(c.path("id").asText());
        String other = buyer("buyer-expired-member");
        call("PUT", progress, other, Map.of("positionSeconds", 40), 403);
        assertThat(call("GET", "/learning/courses", other, null, 200).path("items")).isEmpty();
        var detail = call("GET", "/products/" + c.path("productId").asText(), a, null, 200);
        call(
                "POST",
                "/admin/products/" + c.path("productId").asText() + "/unpublish",
                a,
                Map.of("version", detail.path("version").asLong(), "reason", "DEV off sale"),
                200);
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .path("allowedActions")
                                .toString())
                .contains("LEARN")
                .doesNotContain("BUY");
        call("GET", play(c, paid), b, null, 200);
        jdbc.update("update file_asset set status='DISABLED' where id=?", v.path("id").asText());
        assertThat(call("GET", play(c, paid), b, null, 409).path("code").asText())
                .isEqualTo("FILE_NOT_READY");
    }

    @Test
    void wordPdfAndImageUseExactPaperFileRelationActionAndSealedIdentity() throws Exception {
        var files =
                List.of(
                        upload(
                                a,
                                "PAPER_ATTACHMENT",
                                "DEV.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                DevelopmentFileFixtures.docx()),
                        upload(
                                a,
                                "PAPER_ATTACHMENT",
                                "DEV.pdf",
                                "application/pdf",
                                DevelopmentFileFixtures.pdf()),
                        upload(a, "PAPER_ATTACHMENT", "DEV.png", "image/png", png()));
        String p = paper(files),
                other =
                        paper(
                                List.of(
                                        upload(
                                                a,
                                                "PAPER_ATTACHMENT",
                                                "other.png",
                                                "image/png",
                                                png())));
        String authorize =
                "/papers/" + p + "/files/" + files.getFirst().path("id").asText() + "/authorize";
        call("POST", authorize, b, Map.of("action", "DOWNLOAD"), 403);
        String order = paid(member(6), "2026-10-10T02:00:00Z");
        deliver(order);
        var summary = call("GET", "/papers/" + p, b, null, 200);
        assertThat(summary.path("attachments").get(0).path("canDownload").asBoolean()).isTrue();
        assertThat(summary.toString())
                .doesNotContain("storageKey", "sealedVersion", "/protected-content/");
        call(
                "POST",
                "/papers/"
                        + other
                        + "/files/"
                        + files.getFirst().path("id").asText()
                        + "/authorize",
                b,
                Map.of("action", "DOWNLOAD"),
                404);
        for (var f : files) {
            String path = "/papers/" + p + "/files/" + f.path("id").asText() + "/authorize";
            var preview = call("POST", path, b, Map.of("action", "PREVIEW"), 200);
            var download = call("POST", path, b, Map.of("action", "DOWNLOAD"), 200);
            assertThat(download.path("expiresAt").asText()).isEqualTo("2026-10-10T02:02:00Z");
            var response =
                    mvc.perform(get(download.path("url").asText())).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).isNotEmpty();
            assertThat(response.getHeader("Content-Disposition")).startsWith("attachment");
            assertThat(
                            mvc.perform(get(preview.path("url").asText()))
                                    .andReturn()
                                    .getResponse()
                                    .getHeader("Content-Disposition"))
                    .startsWith("inline");
            var ranged =
                    mvc.perform(get(download.path("url").asText()).header("Range", "bytes=0-3"))
                            .andReturn()
                            .getResponse();
            assertThat(ranged.getStatus()).isEqualTo(206);
            assertThat(ranged.getContentAsByteArray()).hasSize(4);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from download_audit where user_id=? and"
                                        + " action='DOWNLOAD' and result='AUTHORIZED'",
                                Integer.class,
                                uid))
                .isEqualTo(3);
        var url =
                call("POST", authorize, b, Map.of("action", "DOWNLOAD"), 200).path("url").asText();
        jdbc.update(
                "update file_asset set sealed_version='changed' where id=?",
                files.getFirst().path("id").asText());
        assertThat(mvc.perform(get(url)).andReturn().getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void capLifetimeToSubsecondMembershipAndRejectExpiredOrRefundedBearer() throws Exception {
        var f = upload(a, "PAPER_ATTACHMENT", "DEV.png", "image/png", png());
        String p = paper(List.of(f));
        String id = paid(member(6), "2026-04-10T02:00:00.500000Z");
        deliver(id);
        String path = "/papers/" + p + "/files/" + f.path("id").asText() + "/authorize";
        var ticket = call("POST", path, b, Map.of("action", "DOWNLOAD"), 200);
        assertThat(Instant.parse(ticket.path("expiresAt").asText()))
                .isEqualTo(Instant.parse("2026-10-10T02:00:00.500Z"));
        clock.now = Instant.parse("2026-10-10T02:00:00.500Z");
        assertThat(
                        mvc.perform(get(ticket.path("url").asText()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(call("POST", path, b, Map.of("action", "DOWNLOAD"), 403).path("code").asText())
                .isEqualTo("ENTITLEMENT_EXPIRED");
        clock.now = Instant.parse("2026-10-10T02:00:00Z");
        refund(id, true, "cap-refund-" + id);
        assertThat(
                        mvc.perform(get(ticket.path("url").asText()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    @Test
    void memberRecordAdministrationIsPagedReadOnlyAndPermissionChecked() throws Exception {
        deliver(paid(member(6), "2026-10-10T02:00:00Z"));
        call("GET", "/admin/membership/records", b, null, 403);
        String content = admin("CONTENT_EDITOR");
        call("GET", "/admin/membership/records", content, null, 403);
        var records = call("GET", "/admin/membership/records?page=1&pageSize=1", a, null, 200);
        assertThat(records.path("items").size()).isEqualTo(1);
        assertThat(records.path("total").asInt()).isPositive();
        call("GET", "/admin/membership/records?pageSize=101", a, null, 400);
    }

    @Test
    void shorteningDurationRecomputesSummaryAndSamePositionCompletion() throws Exception {
        verifyDurationCompletion(50, 50, false, true);
    }

    @Test
    void extendingDurationRecomputesSummaryAndSamePositionCompletion() throws Exception {
        verifyDurationCompletion(120, 180, true, false);
    }

    void verifyDurationCompletion(int position, int duration, boolean before, boolean after)
            throws Exception {
        var c = course("DAYS", 30);
        var ch = chapter(c, false, video().path("id").asText());
        String order = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        deliver(order);
        var money =
                jdbc.queryForMap(
                        "select payable_amount_fen,refunded_goods_fen from store_order where id=?",
                        order);
        String path = "/learning/chapters/" + ch.path("id").asText() + "/progress";
        var saved = call("PUT", path, b, Map.of("positionSeconds", position), 200);
        assertThat(saved.path("completed").asBoolean()).isEqualTo(before);
        var prior =
                jdbc.queryForMap(
                        "select position_seconds,completed,last_learned_at,version from"
                                + " learning_progress where user_id=? and chapter_id=?",
                        uid,
                        ch.path("id").asText());
        clock.now = clock.now.plusSeconds(5);
        call(
                "PATCH",
                "/admin/chapters/" + ch.path("id").asText(),
                a,
                Map.of(
                        "version",
                        ch.path("version").asLong(),
                        "title",
                        "DEV duration edit",
                        "sortNo",
                        0,
                        "durationSeconds",
                        duration,
                        "isTrial",
                        false,
                        "enabled",
                        true,
                        "videoAssetId",
                        ch.path("videoAssetId").asText()),
                200);
        var summary = call("GET", "/learning/courses", b, null, 200).path("items").get(0);
        var softly = new SoftAssertions();
        softly.assertThat(summary.path("completedChapters").asInt())
                .as("summary before repeat PUT")
                .isEqualTo(after ? 1 : 0);
        assertThat(summary.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForMap(
                                "select position_seconds,completed,last_learned_at,version from"
                                        + " learning_progress where user_id=? and chapter_id=?",
                                uid,
                                ch.path("id").asText()))
                .isEqualTo(prior);
        var updated = call("PUT", path, b, Map.of("positionSeconds", position), 200);
        softly.assertThat(updated.path("completed").asBoolean())
                .as("same-position PUT completion")
                .isEqualTo(after);
        softly.assertThat(updated.path("lastLearnedAt").asText()).isEqualTo(clock.now.toString());
        var current =
                jdbc.queryForMap(
                        "select position_seconds,completed,last_learned_at,version from"
                                + " learning_progress where user_id=? and chapter_id=?",
                        uid,
                        ch.path("id").asText());
        softly.assertThat(((Number) current.get("version")).longValue())
                .isEqualTo(((Number) prior.get("version")).longValue() + 1);
        clock.now = clock.now.plusSeconds(5);
        assertThat(
                        call(
                                "PUT",
                                path,
                                b,
                                Map.of("positionSeconds", position, "completed", !after),
                                200))
                .isEqualTo(updated);
        assertThat(
                        jdbc.queryForMap(
                                "select position_seconds,completed,last_learned_at,version from"
                                        + " learning_progress where user_id=? and chapter_id=?",
                                uid,
                                ch.path("id").asText()))
                .isEqualTo(current);
        assertThat(
                        jdbc.queryForMap(
                                "select payable_amount_fen,refunded_goods_fen from store_order"
                                        + " where id=?",
                                order))
                .isEqualTo(money);
        softly.assertAll();
    }

    @Test
    void progressCompletionHintCannotForgeCompletionAndPlaybackResumesOwnedPosition()
            throws Exception {
        var c = course("DAYS", 30);
        var ch = chapter(c, false, video().path("id").asText());
        deliver(paid(c.path("productId").asText(), "2026-10-06T02:00:00Z"));
        var saved =
                call(
                        "PUT",
                        "/learning/chapters/" + ch.path("id").asText() + "/progress",
                        b,
                        Map.of("positionSeconds", 50, "completed", true),
                        200);
        assertThat(saved.path("completed").asBoolean()).isFalse();
        assertThat(call("GET", play(c, ch), b, null, 200).path("resumeSeconds").asInt(-1))
                .isEqualTo(50);
        call(
                "PATCH",
                "/admin/chapters/" + ch.path("id").asText(),
                a,
                Map.of(
                        "version",
                        ch.path("version").asLong(),
                        "title",
                        "DEV shortened chapter",
                        "sortNo",
                        0,
                        "durationSeconds",
                        30,
                        "isTrial",
                        false,
                        "enabled",
                        true,
                        "videoAssetId",
                        ch.path("videoAssetId").asText()),
                200);
        assertThat(call("GET", play(c, ch), b, null, 200).path("resumeSeconds").asInt())
                .isEqualTo(30);
        assertThat(
                        call("GET", "/learning/courses", b, null, 200)
                                .path("items")
                                .get(0)
                                .path("completedChapters")
                                .asInt())
                .isEqualTo(1);
    }
}
