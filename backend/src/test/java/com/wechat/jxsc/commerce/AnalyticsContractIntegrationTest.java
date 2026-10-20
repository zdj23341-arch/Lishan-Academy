package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Import(FinanceSqlGate.class)
class AnalyticsContractIntegrationTest extends EntitlementTestSupport {
    private static final String RANGE = "?startDate=2026-10-06&endDate=2026-10-10";

    JsonNode overview(boolean dev) throws Exception {
        return call(
                "GET",
                "/admin/analytics/overview" + RANGE + "&includeDevChannel=" + dev,
                a,
                null,
                200);
    }

    void real(String id, String created, String paid) {
        jdbc.update(
                "update store_order set channel='WECHAT_PAY',created_at=?,paid_at=? where id=?",
                Timestamp.from(Instant.parse(created)),
                paid == null ? null : Timestamp.from(Instant.parse(paid)),
                id);
        jdbc.update(
                "update payment_transaction set channel='WECHAT_PAY',paid_at=? where order_id=?",
                paid == null ? null : Timestamp.from(Instant.parse(paid)),
                id);
    }

    @Test
    void noChargeBooksWaitingForShipmentAreIncludedInCurrentOrderCount() throws Exception {
        var before = overview(false);
        String p = book(a, 5, 0);
        long version =
                jdbc.queryForObject(
                        "select version from shipping_rule where province_code='310000'",
                        Long.class);
        call(
                "PUT",
                "/admin/shipping-rules/310000",
                a,
                Map.of(
                        "version",
                        version,
                        "enabled",
                        true,
                        "freightFen",
                        0,
                        "freeThresholdFen",
                        0,
                        "reason",
                        "Zero charge analytics fixture"),
                200);
        try {
            String id =
                    order(b, quote(b, p, address(b).path("id").asText(), 2)).path("id").asText();
            assertThat(call("GET", "/orders/" + id, b, null, 200).path("channel").asText())
                    .isEqualTo("NO_CHARGE");
            var after = overview(false);
            assertThat(after.at("/currentMetrics/waitingShipmentOrderCount").asLong())
                    .isEqualTo(before.at("/currentMetrics/waitingShipmentOrderCount").asLong() + 1);
            assertThat(after.path("pendingShipmentUnits").asLong())
                    .isEqualTo(before.path("pendingShipmentUnits").asLong() + 2);
        } finally {
            long currentVersion =
                    jdbc.queryForObject(
                            "select version from shipping_rule where province_code='310000'",
                            Long.class);
            call(
                    "PUT",
                    "/admin/shipping-rules/310000",
                    a,
                    Map.of(
                            "version",
                            currentVersion,
                            "enabled",
                            true,
                            "freightFen",
                            600,
                            "freeThresholdFen",
                            10000,
                            "reason",
                            "Restore shared shipping fixture"),
                    200);
        }
    }

    @Test
    void unsafeAggregateIsRejectedAndSafeIntegerBoundaryRemainsExact() throws Exception {
        String id = paid(member(6), "2023-03-01T02:00:00Z");
        real(id, "2023-03-01T02:00:00Z", "2023-03-01T02:00:00Z");
        extraPayment(id, 9007199254740891L);
        String path = "/admin/analytics/overview?startDate=2023-03-01&endDate=2023-03-01";
        assertThat(call("GET", path, a, null, 200).at("/periodMetrics/receivedAmountFen").asLong())
                .isEqualTo(9_007_199_254_740_991L);
        extraPayment(id, 1);
        assertThat(call("GET", path, a, null, 422).path("code").asText())
                .isEqualTo("ANALYTICS_RANGE_EXCEEDED");
        assertThat(
                        call("GET", path.replace("/overview", "/trend"), a, null, 422)
                                .path("code")
                                .asText())
                .isEqualTo("ANALYTICS_RANGE_EXCEEDED");
        // MySQL SUM returns DECIMAL, including values exceeding Java long; reject before narrowing.
        extraPayment(id, Long.MAX_VALUE);
        assertThat(call("GET", path, a, null, 422).path("code").asText())
                .isEqualTo("ANALYTICS_RANGE_EXCEEDED");
    }

    private void extraPayment(String id, long amount) {
        // Deliberate analytics-only fixture; copy mandatory frozen channel identity from the
        // source.
        jdbc.update(
                "insert into"
                    + " payment_transaction(order_id,out_trade_no,channel,state,amount_fen,paid_at,created_at,updated_at,app_id,merchant_id)"
                    + " select"
                    + " order_id,?,'WECHAT_PAY','SUCCEEDED',?,paid_at,created_at,updated_at,app_id,merchant_id"
                    + " from payment_transaction where order_id=? order by id limit 1",
                UUID.randomUUID().toString(),
                amount,
                id);
    }

    @Test
    void creationIncludesCancelledUnpaidAndShipmentCountsOrdersRatherThanBooks() throws Exception {
        var baseline = overview(false);
        String p = book(a, 10, 2000), address = address(b).path("id").asText();
        String cancelled = order(b, quote(b, p, address, 1)).path("id").asText();
        write("/orders/" + cancelled + "/cancel", b, Map.of());
        real(cancelled, "2026-10-05T16:00:00Z", null);
        String paid = order(b, quote(b, p, address, 3)).path("id").asText();
        write("/dev/orders/" + paid + "/pay", b, Map.of("scenario", "SUCCESS"));
        real(paid, "2026-10-05T16:00:00Z", "2026-10-06T16:00:00Z");
        // Excluded at the upper Shanghai date boundary.
        String outside = order(b, quote(b, p, address, 1)).path("id").asText();
        real(outside, "2026-10-10T16:00:00Z", null);
        var stats = overview(false);
        assertThat(stats.at("/periodMetrics/orderCreatedCount").asLong())
                .isEqualTo(baseline.at("/periodMetrics/orderCreatedCount").asLong() + 2);
        assertThat(stats.at("/periodMetrics/paidOrderCount").asLong())
                .isEqualTo(baseline.at("/periodMetrics/paidOrderCount").asLong() + 1);
        assertThat(stats.at("/currentMetrics/waitingShipmentOrderCount").asLong())
                .isEqualTo(baseline.at("/currentMetrics/waitingShipmentOrderCount").asLong() + 1);
        assertThat(stats.path("pendingShipmentUnits").asLong())
                .isEqualTo(baseline.path("pendingShipmentUnits").asLong() + 3);
        assertThat(stats.at("/period/timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(stats.path("generatedAt").asText()).isEqualTo(clock.now.toString());
        assertThat(stats.path("environment").asText()).isEqualTo("test");
        assertThat(stats.path("includesDevChannel").asBoolean()).isFalse();
        assertThat(stats.has("visitorCount")).isFalse();
        assertThat(stats.has("wechatShopSales")).isFalse();
    }

    @Test
    void devInclusionIsExplicitAndUsesSameScopeForMembershipSnapshots() throws Exception {
        var realBefore = overview(false);
        var devBefore = overview(true);
        String id = paid(member(6), "2026-10-10T02:00:00Z");
        jdbc.update(
                "update store_order set created_at=? where id=?", Timestamp.from(clock.now), id);
        deliver(id);
        var excluded = overview(false);
        var included = overview(true);
        assertThat(excluded.at("/periodMetrics/receivedAmountFen"))
                .isEqualTo(realBefore.at("/periodMetrics/receivedAmountFen"));
        assertThat(excluded.at("/currentMetrics/activeMemberCount"))
                .isEqualTo(realBefore.at("/currentMetrics/activeMemberCount"));
        assertThat(included.at("/periodMetrics/receivedAmountFen").asLong())
                .isEqualTo(devBefore.at("/periodMetrics/receivedAmountFen").asLong() + 100);
        assertThat(included.at("/currentMetrics/activeMemberCount").asLong())
                .isEqualTo(devBefore.at("/currentMetrics/activeMemberCount").asLong() + 1);
        assertThat(included.path("includesDevChannel").asBoolean()).isTrue();
        assertThat(included.at("/periodMetrics/membershipNewPurchaseCount").asLong())
                .isEqualTo(devBefore.at("/periodMetrics/membershipNewPurchaseCount").asLong() + 1);
        assertThat(included.path("receiptsFen").asLong())
                .isEqualTo(included.at("/periodMetrics/receivedAmountFen").asLong());
        refund(id, true, "analytics-dev-revoke");
        assertThat(overview(true).at("/currentMetrics/activeMemberCount"))
                .isEqualTo(devBefore.at("/currentMetrics/activeMemberCount"));
    }

    @Test
    void expiredHistoryIsRenewalAndUndeliveredPaymentDoesNotCountAsMembershipPurchase()
            throws Exception {
        var baseline = overview(false);
        String p = member(6), old = paid(p, "2025-01-01T02:00:00Z");
        real(old, "2025-01-01T02:00:00Z", "2025-01-01T02:00:00Z");
        deliver(old);
        String renewal = paid(p, "2026-10-10T02:00:00Z");
        real(renewal, "2026-10-10T02:00:00Z", "2026-10-10T02:00:00Z");
        assertThat(overview(false).at("/periodMetrics/membershipRenewalCount"))
                .isEqualTo(baseline.at("/periodMetrics/membershipRenewalCount"));
        deliver(renewal);
        var delivered = overview(false);
        assertThat(delivered.at("/periodMetrics/membershipRenewalCount").asLong())
                .isEqualTo(baseline.at("/periodMetrics/membershipRenewalCount").asLong() + 1);
        assertThat(delivered.at("/periodMetrics/membershipNewPurchaseCount"))
                .isEqualTo(baseline.at("/periodMetrics/membershipNewPurchaseCount"));
        assertThat(delivered.at("/currentMetrics/activeMemberCount").asLong())
                .isEqualTo(baseline.at("/currentMetrics/activeMemberCount").asLong() + 1);
        clock.now = Instant.parse("2027-04-10T02:00:00Z");
        a = admin("SUPER_ADMIN");
        assertThat(overview(false).at("/currentMetrics/activeMemberCount").asLong())
                .isLessThan(delivered.at("/currentMetrics/activeMemberCount").asLong());
        assertThat(overview(false).at("/periodMetrics/membershipRenewalCount"))
                .isEqualTo(delivered.at("/periodMetrics/membershipRenewalCount"));
    }

    @Test
    void trendZeroFillsSortedDatesRetainsLegacyFieldsAndBatchesLongRanges() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (after && sql.startsWith("select")) queries.incrementAndGet();
                };
        JsonNode result;
        try {
            result =
                    call(
                            "GET",
                            "/admin/analytics/trend?startDate=2024-01-01&endDate=2024-12-31",
                            a,
                            null,
                            200);
        } finally {
            FinanceSqlGate.observer = (sql, after) -> {};
        }
        assertThat(queries.get())
                .as("year-long trends must batch by event date, not query per day")
                .isLessThan(100);
        System.out.println("366-day analytics JDBC SELECT count: " + queries.get());
        assertThat(result.path("items").size()).isEqualTo(366);
        assertThat(result.at("/period/startDate").asText()).isEqualTo("2024-01-01");
        for (int i = 0; i < 366; i++) {
            var row = result.path("items").get(i);
            assertThat(row.path("date").asText())
                    .isEqualTo(java.time.LocalDate.parse("2024-01-01").plusDays(i).toString());
            assertThat(row.path("orderCreatedCount").asLong()).isZero();
            assertThat(row.path("netCashAmountFen").asLong()).isZero();
            assertThat(row.has("receiptsFen")).isTrue();
            assertThat(row.has("downloads")).isTrue();
        }
        call("GET", "/admin/analytics/overview" + RANGE, b, null, 403);
        call("GET", "/admin/analytics/trend" + RANGE, admin("CONTENT_EDITOR"), null, 403);
        call("GET", "/admin/analytics/trend?startDate=2024-01-01&endDate=2025-01-01", a, null, 400);
    }
}
