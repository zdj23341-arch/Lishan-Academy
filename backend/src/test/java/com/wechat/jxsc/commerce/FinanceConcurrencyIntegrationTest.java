package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.distribution.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

class FinanceConcurrencyIntegrationTest extends DistributionTestSupport {
    @Autowired javax.sql.DataSource dataSource;
    @Autowired CommissionService commissions;

    String source(String pa, boolean consume) throws Exception {
        String p = book(a, 5, 80000);
        rule(p, did(pa));
        touch(pa, p);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        if (consume) {
            events.handle("ORDER_PAID", id, "{}");
            jdbc.update(
                    "update store_order set completed_at=? where id=?",
                    Timestamp.from(clock.now.minusSeconds(604800)),
                    id);
            commissions.mature(Long.parseLong(id));
        }
        return id;
    }

    JsonNode withdrawal(String token) throws Exception {
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/v1/distribution/withdrawals")
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content("{\"amountFen\":8000}"))
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsByteArray());
    }

    void waitForLock() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            if (jdbc.queryForObject(
                            "select count(distinct requesting_engine_transaction_id) from"
                                    + " performance_schema.data_lock_waits",
                            Long.class)
                    > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected a real InnoDB lock wait");
    }

    @Test
    void committedHoldWinsAgainstWithdrawalAlreadyWaitingForItsSourceOrder() throws Exception {
        String pa = freshPromoter(), order = source(pa, true);
        var pool = Executors.newSingleThreadExecutor();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var p = c.prepareStatement("select id from store_order where id=? for update")) {
                p.setLong(1, Long.parseLong(order));
                p.executeQuery();
            }
            var result = pool.submit(() -> withdrawal(pa));
            waitForLock();
            try (var p =
                    c.prepareStatement("update store_order set financial_hold=true where id=?")) {
                p.setLong(1, Long.parseLong(order));
                p.executeUpdate();
            }
            c.commit();
            assertThat(result.get(10, TimeUnit.SECONDS).path("code").asText())
                    .isEqualTo("FINANCIAL_RECONCILIATION_REQUIRED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        call("GET", "/distribution/summary", pa, null, 200)
                                .path("availableFen")
                                .asLong())
                .isEqualTo(10000);
    }

    @Test
    void sourceAppearingAfterInitialScanRetriesWithItsOrderBeforeFreezing() throws Exception {
        String pa = freshPromoter();
        source(pa, true);
        String added = source(pa, false);
        long did = Long.parseLong(did(pa));
        var pool = Executors.newFixedThreadPool(2);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var p = c.prepareStatement("select id from distributor where id=? for update")) {
                p.setLong(1, did);
                p.executeQuery();
            }
            var accrue =
                    pool.submit(
                            () -> {
                                events.handle("ORDER_PAID", added, "{}");
                                return true;
                            });
            waitForLock();
            var request = pool.submit(() -> withdrawal(pa));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jdbc.queryForObject(
                                    "select count(distinct requesting_engine_transaction_id) from"
                                            + " performance_schema.data_lock_waits",
                                    Long.class)
                            < 2
                    && System.nanoTime() < until) Thread.sleep(10);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(distinct requesting_engine_transaction_id) from"
                                            + " performance_schema.data_lock_waits",
                                    Long.class))
                    .isGreaterThanOrEqualTo(2);
            c.commit();
            assertThat(accrue.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request.get(10, TimeUnit.SECONDS).path("code").asText()).isEqualTo("OK");
        } finally {
            pool.shutdownNow();
        }
        var summary = call("GET", "/distribution/summary", pa, null, 200);
        assertThat(summary.path("pendingFen").asLong()).isEqualTo(10000);
        assertThat(summary.path("availableFen").asLong()).isEqualTo(2000);
        assertThat(summary.path("withdrawingFen").asLong()).isEqualTo(8000);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " distributor_id=? and event_type='WITHDRAWAL_FREEZE'",
                                Long.class,
                                did))
                .isEqualTo(1);
    }
}
