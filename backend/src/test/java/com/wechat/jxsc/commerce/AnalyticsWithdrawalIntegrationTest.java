package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.distribution.CommissionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.*;

class AnalyticsWithdrawalIntegrationTest extends DistributionTestSupport {
    @Autowired CommissionService commissions;

    com.fasterxml.jackson.databind.JsonNode stats(boolean dev) throws Exception {
        return call(
                "GET",
                "/admin/analytics/overview?startDate=2026-10-10&endDate=2026-10-10&includeDevChannel="
                        + dev,
                a,
                null,
                200);
    }

    @Test
    void unknownTransferStaysInTransitAndOnlyConfirmedSuccessCountsAsOutflow() throws Exception {
        var before = stats(true);
        var realBefore = stats(false);
        String promoter = freshPromoter(), p = book(a, 5, 80000);
        rule(p, did(promoter));
        touch(promoter, p);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", id, "{}");
        jdbc.update(
                "update store_order set order_status='COMPLETED',completed_at=? where id=?",
                Timestamp.from(clock.now.minusSeconds(604800)),
                id);
        commissions.mature(Long.parseLong(id));
        String wid =
                write("/distribution/withdrawals", promoter, Map.of("amountFen", 8000))
                        .path("id")
                        .asText();
        write("/dev/withdrawals/" + wid + "/simulate", promoter, Map.of("scenario", "TIMEOUT"));
        assertThat(
                        call("GET", "/distribution/withdrawals/" + wid, promoter, null, 200)
                                .path("status")
                                .asText())
                .isEqualTo("UNKNOWN");
        var unknown = stats(true);
        assertThat(unknown.at("/periodMetrics/commissionEarnedFen").asLong())
                .isEqualTo(before.at("/periodMetrics/commissionEarnedFen").asLong() + 10000);
        assertThat(unknown.at("/periodMetrics/withdrawalSucceededFen"))
                .isEqualTo(before.at("/periodMetrics/withdrawalSucceededFen"));
        assertThat(unknown.at("/currentMetrics/withdrawingFen").asLong())
                .isEqualTo(before.at("/currentMetrics/withdrawingFen").asLong() + 8000);
        assertThat(stats(false).path("periodMetrics")).isEqualTo(realBefore.path("periodMetrics"));
        assertThat(stats(false).path("currentMetrics"))
                .isEqualTo(realBefore.path("currentMetrics"));
        write(
                "/dev/withdrawals/" + wid + "/simulate",
                promoter,
                Map.of("scenario", "SUCCESS_QUERY"));
        write(
                "/dev/withdrawals/" + wid + "/simulate",
                promoter,
                Map.of("scenario", "SUCCESS_QUERY"));
        var success = stats(true);
        assertThat(success.at("/periodMetrics/withdrawalSucceededFen").asLong())
                .isEqualTo(before.at("/periodMetrics/withdrawalSucceededFen").asLong() + 8000);
        assertThat(success.at("/currentMetrics/withdrawingFen"))
                .isEqualTo(before.at("/currentMetrics/withdrawingFen"));
        assertThat(success.at("/currentMetrics/availableCommissionFen").asLong())
                .isEqualTo(before.at("/currentMetrics/availableCommissionFen").asLong() + 2000);
        assertThat(stats(false).at("/periodMetrics/withdrawalSucceededFen"))
                .isEqualTo(realBefore.at("/periodMetrics/withdrawalSucceededFen"));
    }
}
