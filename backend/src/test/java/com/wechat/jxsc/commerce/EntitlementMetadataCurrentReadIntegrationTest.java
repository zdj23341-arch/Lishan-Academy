package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.paper.ContentAuthorization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

class EntitlementMetadataCurrentReadIntegrationTest extends EntitlementTestSupport {
    @Autowired ContentAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;

    void concurrent(Runnable action) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                executor.submit(
                                () ->
                                        new TransactionTemplate(transactions)
                                                .executeWithoutResult(s -> action.run()))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void paperBearerCannotUseAttachmentRemovedAfterEarlierRepeatableReadSnapshot()
            throws Exception {
        var f = upload(a, "PAPER_ATTACHMENT", "DEV.png", "image/png", png());
        var p =
                call(
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
                                List.of(
                                        Map.of(
                                                "assetId",
                                                f.path("id").asText(),
                                                "title",
                                                "DEV file",
                                                "sortNo",
                                                0))),
                        200);
        deliver(paid(member(6), "2026-10-10T02:00:00Z"));
        String url =
                call(
                                "POST",
                                "/papers/"
                                        + p.path("id").asText()
                                        + "/files/"
                                        + f.path("id").asText()
                                        + "/authorize",
                                b,
                                Map.of("action", "DOWNLOAD"),
                                200)
                        .path("url")
                        .asText();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(
                                                s -> {
                                                    jdbc.queryForObject(
                                                            "select count(*) from paper_attachment",
                                                            Integer.class);
                                                    concurrent(
                                                            () ->
                                                                    jdbc.update(
                                                                            "delete from"
                                                                                + " paper_attachment"
                                                                                + " where"
                                                                                + " paper_id=?",
                                                                            p.path("id").asText()));
                                                    return authorization.redeem(
                                                            url.substring(
                                                                    url.lastIndexOf('/') + 1));
                                                }))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class)
                .hasMessage("Resource not found");
    }

    @Test
    void playbackBearerCannotUseChapterDisabledAfterEarlierSnapshot() throws Exception {
        var c = course("DAYS", 30);
        byte[] bytes =
                HexFormat.of()
                        .parseHex(
                                "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000");
        var v = upload(a, "COURSE_VIDEO", "DEV.mp4", "video/mp4", bytes);
        var ch =
                call(
                        "POST",
                        "/admin/courses/" + c.path("id").asText() + "/chapters",
                        a,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV chapter",
                                "sortNo",
                                0,
                                "durationSeconds",
                                120,
                                "isTrial",
                                true,
                                "enabled",
                                true,
                                "videoAssetId",
                                v.path("id").asText()),
                        200);
        String url =
                call(
                                "GET",
                                "/courses/"
                                        + c.path("id").asText()
                                        + "/chapters/"
                                        + ch.path("id").asText()
                                        + "/play-url",
                                null,
                                null,
                                200)
                        .path("url")
                        .asText();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(
                                                s -> {
                                                    jdbc.queryForObject(
                                                            "select count(*) from course_chapter",
                                                            Integer.class);
                                                    concurrent(
                                                            () ->
                                                                    jdbc.update(
                                                                            "update course_chapter"
                                                                                + " set enabled=false"
                                                                                + " where id=?",
                                                                            ch.path("id")
                                                                                    .asText()));
                                                    return authorization.redeem(
                                                            url.substring(
                                                                    url.lastIndexOf('/') + 1));
                                                }))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class)
                .hasMessage("Chapter or file is not ready");
    }
}
