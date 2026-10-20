package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

class FinanceAnalyticsIntegrationTest extends EntitlementTestSupport {
    @Test
    void shanghaiReceiptAndRefundUseSeparateDatesAndExcludeDev() throws Exception {
        String p = book(a, 4, 9400);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        String caseId =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Analytics test",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        2000,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + caseId + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        2000,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "Analytics test"));
        // Controlled database fixture for analytics only; no real merchant acceptance is implied.
        jdbc.update(
                "update store_order set channel='WECHAT_PAY',paid_at=? where id=?",
                Timestamp.from(Instant.parse("2026-10-05T16:00:00Z")),
                id);
        jdbc.update(
                "update payment_transaction set channel='WECHAT_PAY',paid_at=? where order_id=?",
                Timestamp.from(Instant.parse("2026-10-05T16:00:00Z")),
                id);
        jdbc.update(
                "update refund_transaction set channel='WECHAT_PAY',state='SUCCEEDED',success_at=?"
                        + " where order_id=?",
                Timestamp.from(Instant.parse("2026-10-06T16:00:00Z")),
                id);
        var oct6 =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=2026-10-06&endDate=2026-10-06",
                        a,
                        null,
                        200);
        var oct7 =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=2026-10-07&endDate=2026-10-07",
                        a,
                        null,
                        200);
        var both =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=2026-10-06&endDate=2026-10-07",
                        a,
                        null,
                        200);
        assertThat(oct6.path("receiptsFen").asLong()).isEqualTo(10000);
        assertThat(oct6.path("refundsFen").asLong()).isZero();
        assertThat(oct6.path("netReceiptsFen").asLong()).isEqualTo(10000);
        assertThat(oct7.path("receiptsFen").asLong()).isZero();
        assertThat(oct7.path("refundsFen").asLong()).isEqualTo(2000);
        assertThat(oct7.path("netReceiptsFen").asLong()).isEqualTo(-2000);
        assertThat(both.path("netReceiptsFen").asLong()).isEqualTo(8000);
        assertThat(both.path("paidOrders").asLong()).isEqualTo(1);
        assertThat(both.at("/periodMetrics/receivedAmountFen").asLong()).isEqualTo(10000);
        assertThat(both.at("/periodMetrics/refundedAmountFen").asLong()).isEqualTo(2000);
        assertThat(both.at("/periodMetrics/netCashAmountFen").asLong()).isEqualTo(8000);
        var trend =
                call(
                        "GET",
                        "/admin/analytics/trend?startDate=2026-10-06&endDate=2026-10-08",
                        a,
                        null,
                        200);
        assertThat(trend.at("/items/0/date").asText()).isEqualTo("2026-10-06");
        assertThat(trend.at("/items/0/receivedAmountFen").asLong()).isEqualTo(10000);
        assertThat(trend.at("/items/1/refundedAmountFen").asLong()).isEqualTo(2000);
        assertThat(trend.at("/items/1/netCashAmountFen").asLong()).isEqualTo(-2000);
        assertThat(trend.at("/items/2/netCashAmountFen").asLong()).isZero();
        jdbc.update("update payment_transaction set channel='DEV' where order_id=?", id);
        assertThat(
                        call(
                                        "GET",
                                        "/admin/analytics/overview?startDate=2026-10-06&endDate=2026-10-07",
                                        a,
                                        null,
                                        200)
                                .path("receiptsFen")
                                .asLong())
                .isZero();
    }

    @Test
    void exportIsAsynchronousPrivateOwnedAndRequiresItsReadPermission() throws Exception {
        String analyst = admin("ANALYST"),
                other = admin("ANALYST"),
                editor = admin("CONTENT_EDITOR");
        var e =
                call(
                        "POST",
                        "/admin/exports",
                        analyst,
                        Map.of(
                                "type",
                                "ANALYTICS",
                                "startDate",
                                "2026-10-06",
                                "endDate",
                                "2026-10-07"),
                        200);
        assertThat(e.path("status").asText()).isEqualTo("REQUESTED");
        String id = e.path("id").asText();
        call("GET", "/admin/exports/" + id, other, null, 404);
        call(
                "POST",
                "/admin/exports",
                editor,
                Map.of("type", "ANALYTICS", "startDate", "2026-10-06", "endDate", "2026-10-07"),
                403);
        worker.run(100);
        assertThat(call("GET", "/admin/exports/" + id, analyst, null, 200).path("status").asText())
                .isEqualTo("SUCCEEDED");
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/admin/exports/" + id + "/content")
                                        .header("Authorization", "Bearer " + analyst))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentAsString()).contains("receiptsFen");
        clock.now = clock.now.plusSeconds(901);
        call("GET", "/admin/exports/" + id + "/content", analyst, null, 404);
    }
}
