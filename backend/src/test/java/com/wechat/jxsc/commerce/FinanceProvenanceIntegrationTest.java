package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceProvenanceIntegrationTest extends EntitlementTestSupport {
    String attachment() throws Exception {
        var file = upload(a, "PAPER_ATTACHMENT", "DEV.png", "image/png", png());
        String fid = file.path("id").asText();
        String paper =
                call(
                                "POST",
                                "/admin/papers",
                                a,
                                Map.of(
                                        "version",
                                        0,
                                        "title",
                                        "DEV provenance paper",
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
                                                        fid,
                                                        "title",
                                                        "DEV file",
                                                        "sortNo",
                                                        0))),
                                200)
                        .path("id")
                        .asText();
        return "/papers/" + paper + "/files/" + fid + "/authorize";
    }

    @Test
    void expiredRealHistoryDoesNotTurnAnOrdinaryDevMembershipDownloadIntoARealMetric()
            throws Exception {
        String product = member(6), old = paid(product, "2025-01-01T02:00:00Z");
        jdbc.update("update store_order set channel='WECHAT_PAY' where id=?", old);
        jdbc.update("update payment_transaction set channel='WECHAT_PAY' where order_id=?", old);
        deliver(old);
        String active = paid(product, "2026-10-10T02:00:00Z");
        deliver(active);
        String url = attachment();
        call("POST", url, b, Map.of("action", "DOWNLOAD"), 200);
        call("POST", url, b, Map.of("action", "PREVIEW"), 200);
        var stats =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=2026-10-01&endDate=2026-10-31",
                        a,
                        null,
                        200);
        assertThat(stats.path("downloads").asLong()).isZero();
        assertThat(stats.path("previews").asLong()).isZero();
        assertThat(stats.path("activeMembers").asLong()).isZero();
    }

    @Test
    void realDownloadKeepsHistoricalSourceAfterMembershipRefundReplay() throws Exception {
        String id = paid(member(6), "2026-10-10T02:00:00Z");
        jdbc.update("update store_order set channel='WECHAT_PAY' where id=?", id);
        jdbc.update("update payment_transaction set channel='WECHAT_PAY' where order_id=?", id);
        deliver(id);
        call("POST", attachment(), b, Map.of("action", "DOWNLOAD"), 200);
        refund(id, true, "provenance-refund");
        var stats =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=2026-10-10&endDate=2026-10-10",
                        a,
                        null,
                        200);
        assertThat(stats.path("downloads").asLong()).isEqualTo(1);
        assertThat(stats.path("activeMembers").asLong()).isZero();
    }
}
