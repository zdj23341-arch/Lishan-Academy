package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.content.DevelopmentFileFixtures;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

class PaperDownloadIntegrationTest extends EntitlementTestSupport {
    JsonNode pdf() throws Exception {
        return upload(
                a, "PAPER_ATTACHMENT", "DEV.pdf", "application/pdf", DevelopmentFileFixtures.pdf());
    }

    String paper(JsonNode file) throws Exception {
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
                                List.of(
                                        Map.of(
                                                "assetId",
                                                file.path("id").asText(),
                                                "title",
                                                "DEV file",
                                                "sortNo",
                                                0))),
                        200)
                .path("id")
                .asText();
    }

    @Test
    void listsOnlyTheCallersOwnFilesOnceAndGatesRedownloadOnMembership() throws Exception {
        var file = pdf();
        String paper = paper(file);
        String authorize =
                "/papers/" + paper + "/files/" + file.path("id").asText() + "/authorize";
        call("GET", "/downloads", null, null, 401);
        // A refused authorization must not create history.
        call("POST", authorize, b, Map.of("action", "DOWNLOAD"), 403);
        assertThat(call("GET", "/downloads", b, null, 200)).isEmpty();
        deliver(paid(member(6), "2026-10-10T02:00:00Z"));
        call("POST", authorize, b, Map.of("action", "DOWNLOAD"), 200);
        call("POST", authorize, b, Map.of("action", "PREVIEW"), 200);
        var rows = call("GET", "/downloads", b, null, 200);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.path("paperId").asText()).isEqualTo(paper);
        assertThat(row.path("fileId").asText()).isEqualTo(file.path("id").asText());
        assertThat(row.path("fileName").asText()).isEqualTo("DEV.pdf");
        assertThat(row.path("action").asText()).isEqualTo("PREVIEW");
        assertThat(row.path("times").asInt()).isEqualTo(2);
        assertThat(row.path("canDownload").asBoolean()).isTrue();
        assertThat(row.path("unavailableReason").isTextual()).isFalse();
        assertThat(row.path("lastAt").asText()).endsWith("Z");
        // Re-opening the same file keeps a single row with a growing count.
        call("POST", authorize, b, Map.of("action", "DOWNLOAD"), 200);
        var again = call("GET", "/downloads", b, null, 200);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).path("times").asInt()).isEqualTo(3);
        // History is per subject.
        String other = buyer("buyer-active-member");
        assertThat(call("GET", "/downloads", other, null, 200)).isEmpty();
        // Expiry keeps the record readable but refuses a re-download with an explicit reason.
        jdbc.update(
                "update membership_interval set starts_at=?,expires_at=? where user_id=?",
                Timestamp.from(Instant.parse("2026-10-09T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-10-10T00:00:00Z")),
                uid);
        var expired = call("GET", "/downloads", b, null, 200).get(0);
        assertThat(expired.path("canDownload").asBoolean()).isFalse();
        assertThat(expired.path("unavailableReason").asText()).isEqualTo("MEMBERSHIP_REQUIRED");
        assertThat(expired.path("times").asInt()).isEqualTo(3);
        call("GET", "/downloads?limit=0", b, null, 400);
        call("GET", "/downloads?limit=51", b, null, 400);
        assertThat(call("GET", "/downloads?limit=1", b, null, 200)).hasSize(1);
    }
}
