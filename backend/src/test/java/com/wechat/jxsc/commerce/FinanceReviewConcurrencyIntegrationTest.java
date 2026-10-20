package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.analytics.ExportService;
import com.wechat.jxsc.distribution.CommissionService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Import(FinanceSqlGate.class)
class FinanceReviewConcurrencyIntegrationTest extends DistributionTestSupport {
    @Autowired CommissionService commissions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ExportService exports;

    @AfterEach
    void clearGate() {
        FinanceSqlGate.observer = (sql, after) -> {};
    }

    JsonNode request(String token) throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/distribution/withdrawals")
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content("{\"amountFen\":1000}"))
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsByteArray());
    }

    @Test
    void concurrentDifferentKeysRespectCurrentDailyCountAfterAdmissionWait() throws Exception {
        String promoter = freshPromoter(), d = did(promoter), product = book(a, 5, 80000);
        rule(product, d);
        touch(promoter, product);
        String order =
                order(b, quote(b, product, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + order + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", order, "{}");
        jdbc.update(
                "update store_order set completed_at=? where id=?",
                Timestamp.from(clock.now.minusSeconds(604800)),
                order);
        commissions.mature(Long.parseLong(order));
        var settings = call("GET", "/admin/settings", a, null, 200);
        call(
                "PATCH",
                "/admin/settings",
                a,
                Map.of(
                        "version",
                        settings.path("version").asLong(),
                        "values",
                        Map.of("dailyMaxCount", 1),
                        "reason",
                        "Daily race"),
                200);
        CountDownLatch inserted = new CountDownLatch(1),
                release = new CountDownLatch(1),
                secondSnapshot = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (after
                            && sql.startsWith("insert into withdrawal_request")
                            && first.compareAndSet(true, false)) {
                        inserted.countDown();
                        FinanceSqlGate.await(release);
                    }
                    if (!after
                            && sql.equals(
                                    "select state from account_lifecycle where user_id=? for"
                                            + " update")
                            && inserted.getCount() == 0) secondSnapshot.countDown();
                };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> request(promoter));
            FinanceSqlGate.await(inserted);
            var two = executor.submit(() -> request(promoter));
            FinanceSqlGate.await(secondSnapshot);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jdbc.queryForObject(
                                    "select count(*) from performance_schema.data_lock_waits",
                                    Long.class)
                            == 0
                    && System.nanoTime() < until) Thread.sleep(10);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from performance_schema.data_lock_waits",
                                    Long.class))
                    .isPositive();
            release.countDown();
            assertThat(
                            List.of(
                                    one.get(10, TimeUnit.SECONDS).path("code").asText(),
                                    two.get(10, TimeUnit.SECONDS).path("code").asText()))
                    .containsExactlyInAnyOrder("OK", "WITHDRAWAL_LIMIT");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from withdrawal_request where"
                                            + " distributor_id=?",
                                    Long.class,
                                    d))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from commission_account_ledger where"
                                        + " distributor_id=? and event_type='WITHDRAWAL_FREEZE'",
                                    Long.class,
                                    d))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select available_fen from commission_account where"
                                            + " distributor_id=?",
                                    Long.class,
                                    d))
                    .isEqualTo(9000);
        } finally {
            release.countDown();
            executor.shutdownNow();
            clearGate();
            jdbc.update("update finance_settings set daily_max_count=5 where id=1");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trendAndAnalyticsExportUseOneSnapshotDuringPaymentCommit(boolean export) throws Exception {
        String order = paid(member(6), "2026-10-10T02:00:00Z");
        jdbc.update("update store_order set channel='WECHAT_PAY' where id=?", order);
        jdbc.update(
                "update payment_transaction set channel='WECHAT_PAY',state='PROCESSING' where"
                        + " order_id=?",
                order);
        var baseline =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=2026-10-10&endDate=2026-10-10",
                        a,
                        null,
                        200);
        long exportId =
                export
                        ? Long.parseLong(
                                call(
                                                "POST",
                                                "/admin/exports",
                                                a,
                                                Map.of(
                                                        "type",
                                                        "ANALYTICS",
                                                        "startDate",
                                                        "2026-10-10",
                                                        "endDate",
                                                        "2026-10-10"),
                                                200)
                                        .path("id")
                                        .asText())
                        : 0;
        CountDownLatch receiptsRead = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean(true);
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (after
                            && sql.startsWith("select coalesce(sum(p.amount_fen),0)")
                            && sql.contains("from payment_transaction")
                            && once.compareAndSet(true, false)) {
                        receiptsRead.countDown();
                        FinanceSqlGate.await(resume);
                    }
                };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result =
                    executor.submit(
                            () ->
                                    export
                                            ? exports.generate(exportId).csv()
                                            : call(
                                                            "GET",
                                                            "/admin/analytics/trend?startDate=2026-10-10&endDate=2026-10-10",
                                                            a,
                                                            null,
                                                            200)
                                                    .toString());
            FinanceSqlGate.await(receiptsRead);
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            tx ->
                                    jdbc.update(
                                            "update payment_transaction set state='SUCCEEDED' where"
                                                    + " order_id=?",
                                            order));
            resume.countDown();
            String value = result.get(10, TimeUnit.SECONDS);
            if (export) {
                String[] lines = value.strip().split("\\R"),
                        names = lines[0].split(","),
                        values = lines[1].split(",");
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < names.length; i++)
                    row.put(names[i].replace("\"", ""), values[i].replace("\"", ""));
                assertThat(Long.parseLong(row.get("receiptsFen")))
                        .isEqualTo(baseline.path("receiptsFen").asLong());
                assertThat(Long.parseLong(row.get("paidOrders")))
                        .isEqualTo(baseline.path("paidOrders").asLong());
            } else {
                var row = json.readTree(value).path("items").get(0);
                assertThat(row.path("receiptsFen").asLong())
                        .isEqualTo(baseline.path("receiptsFen").asLong());
                assertThat(row.path("paidOrders").asLong())
                        .isEqualTo(baseline.path("paidOrders").asLong());
            }
        } finally {
            resume.countDown();
            executor.shutdownNow();
            clearGate();
        }
    }
}
