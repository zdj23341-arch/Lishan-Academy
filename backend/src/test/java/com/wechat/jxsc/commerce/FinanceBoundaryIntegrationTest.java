package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.tasks.TaskHandler;
import com.wechat.jxsc.distribution.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.*;

class FinanceBoundaryIntegrationTest extends DistributionTestSupport {
    @Test
    void allProductTypesShareOneAttributionAndFreeOrdersHaveNoCashPayment() throws Exception {
        String pa = freshPromoter();
        for (String product : List.of(course("DAYS", 30).path("productId").asText(), member(6))) {
            rule(product, did(pa));
            touch(pa, product);
            String id = paid(product, "2026-10-10T02:00:00Z");
            events.handle("ORDER_PAID", id, "{}");
            assertThat(
                            jdbc.queryForObject(
                                    "select earned_fen from commission_ledger where order_id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(12);
        }
        var p = createProduct(a, "COURSE", "DEV free finance", 0);
        String pid = p.path("id").asText();
        call(
                "POST",
                "/admin/courses",
                a,
                Map.of("version", 0, "productId", pid, "validityType", "DAYS", "validityDays", 30),
                200);
        publish(a, p);
        rule(pid, did(pa));
        touch(pa, pid);
        String id =
                order(b, write("/orders/preview", b, Map.of("productId", pid, "quantity", 1)))
                        .path("id")
                        .asText();
        events.handle("ORDER_PAID", id, "{}");
        deliver(id);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_transaction where order_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select earned_fen from commission_ledger where order_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select channel from store_order where id=?", String.class, id))
                .isEqualTo("NO_CHARGE");
    }

    @Test
    void activeAftersalePausesThenRejectionUsesOriginalMaturityDate() throws Exception {
        String pa = freshPromoter(), p = book(a, 5, 80000);
        rule(p, did(pa));
        touch(pa, p);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", id, "{}");
        jdbc.update(
                "update store_order set completed_at=? where id=?",
                Timestamp.from(clock.now.minusSeconds(6 * 86400L)),
                id);
        events.handle("ORDER_COMPLETED", id, "{}");
        String cid =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "pause maturity",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        100,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        clock.now = clock.now.plusSeconds(86400);
        assertThatThrownBy(() -> commissions.mature(Long.parseLong(id)))
                .isInstanceOf(TaskHandler.RetryLater.class);
        a = admin("SUPER_ADMIN");
        write("/admin/aftersales/" + cid + "/reject", a, Map.of("reason", "No refund due"));
        commissions.mature(Long.parseLong(id));
        assertThat(
                        call("GET", "/distribution/summary", pa, null, 200)
                                .path("availableFen")
                                .asLong())
                .isEqualTo(10000);
    }

    @Autowired CommissionService commissions;
    @Autowired WithdrawalTransactions transactions;
    @Autowired WithdrawalWorkflow workflow;

    String paidSource(String pa) throws Exception {
        String p = book(a, 5, 80000);
        rule(p, did(pa));
        touch(pa, p);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", id, "{}");
        jdbc.update(
                "update store_order set completed_at=? where id=?",
                Timestamp.from(clock.now.minusSeconds(604800)),
                id);
        commissions.mature(Long.parseLong(id));
        return id;
    }

    @Test
    void forgedOtherProductAndClaimTheftAreRejectedAndSelfPurchaseHasNoAttribution()
            throws Exception {
        String pa = freshPromoter(), p = book(a, 5, 9990);
        rule(p, did(pa));
        String touch = touch(pa, p);
        var q = quote(b, p, address(b).path("id").asText(), 1);
        write(
                "POST",
                "/orders",
                b,
                Map.of("quoteId", q.path("quoteId").asText(), "attributionToken", "forged"),
                UUID.randomUUID().toString(),
                400);
        String other = book(a, 5, 9990);
        q = quote(b, other, address(b).path("id").asText(), 1);
        write(
                "POST",
                "/orders",
                b,
                Map.of("quoteId", q.path("quoteId").asText(), "attributionToken", touch),
                UUID.randomUUID().toString(),
                400);
        call("POST", "/referrals/claim", pa, Map.of("attributionToken", touch), 400);
        b = pa;
        String self = touch(pa, p);
        q = quote(pa, p, address(pa).path("id").asText(), 1);
        String id =
                write(
                                "/orders",
                                pa,
                                Map.of(
                                        "quoteId",
                                        q.path("quoteId").asText(),
                                        "attributionToken",
                                        self))
                        .path("id")
                        .asText();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from attribution where order_id=?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void changedRulesDoNotRewriteSourceAndCumulativeRefundsDoNotDrift() throws Exception {
        String pa = freshPromoter(), p = book(a, 5, 9990);
        rule(p, did(pa));
        touch(pa, p);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        call(
                "PUT",
                "/admin/products/" + p + "/distributors",
                a,
                Map.of(
                        "version",
                        1,
                        "rules",
                        List.of(Map.of("distributorId", did(pa), "rateBps", 5000, "enabled", true)),
                        "reason",
                        "Future orders only"),
                200);
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", id, "{}");
        assertThat(
                        jdbc.queryForObject(
                                "select earned_fen from commission_ledger where order_id=?",
                                Long.class,
                                id))
                .isEqualTo(1248);
        for (int amount : List.of(1, 2, 3, 3330)) {
            jdbc.update("update store_order set refunded_goods_fen=? where id=?", amount, id);
            events.handle(
                    "REFUND_SUCCEEDED",
                    "small-" + id + "-" + amount,
                    json.writeValueAsString(Map.of("orderId", id)));
        }
        assertThat(
                        jdbc.queryForObject(
                                "select reversed_fen from commission_ledger where order_id=?",
                                Long.class,
                                id))
                .isEqualTo(416);
        assertThat(
                        jdbc.queryForObject(
                                "select pending_fen from commission_account where distributor_id=?",
                                Long.class,
                                did(pa)))
                .isEqualTo(832);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update commission_ledger set rate_bps=9999 where"
                                                + " order_id=?",
                                        id))
                .isInstanceOf(Exception.class);
    }

    @Test
    void dispatchCrashQueriesOriginalAndMismatchedEvidenceNeverReleasesFunds() throws Exception {
        String pa = freshPromoter();
        paidSource(pa);
        String id =
                write("/distribution/withdrawals", pa, Map.of("amountFen", 8000))
                        .path("id")
                        .asText();
        var action = transactions.prepare(Long.parseLong(id));
        assertThat(action.send()).isTrue();
        clock.now = clock.now.plusSeconds(61);
        assertThatThrownBy(() -> workflow.run(Long.parseLong(id)))
                .isInstanceOf(TaskHandler.RetryLater.class);
        assertThat(
                        jdbc.queryForObject(
                                "select send_count from transfer_simulation where withdrawal_id=?",
                                Integer.class,
                                id))
                .isZero();
        var i = action.intent();
        transactions.observe(
                i.id(),
                new TransferProvider.Observation(
                        "wrong-recipient",
                        i.outBillNo(),
                        i.amountFen(),
                        i.appId(),
                        i.merchantId(),
                        "attacker",
                        i.channel(),
                        "DEV" + id,
                        "SUCCESS",
                        null,
                        true),
                "TEST_CALLBACK");
        assertThat(
                        call("GET", "/distribution/withdrawals/" + id, pa, null, 200)
                                .path("status")
                                .asText())
                .isEqualTo("UNKNOWN");
        assertThat(
                        call("GET", "/distribution/summary", pa, null, 200)
                                .path("withdrawingFen")
                                .asLong())
                .isEqualTo(8000);
    }

    @Test
    void conflictingSuccessAfterFailureFreezesFurtherWithdrawalForReconciliation()
            throws Exception {
        String pa = freshPromoter();
        paidSource(pa);
        String id =
                write("/distribution/withdrawals", pa, Map.of("amountFen", 1000))
                        .path("id")
                        .asText();
        write("/dev/withdrawals/" + id + "/simulate", pa, Map.of("scenario", "FAILURE"));
        write("/dev/withdrawals/" + id + "/simulate", pa, Map.of("scenario", "SUCCESS_CALLBACK"));
        assertThat(
                        call("GET", "/distribution/summary", pa, null, 200)
                                .path("withdrawalEnabled")
                                .asBoolean())
                .isFalse();
        write(
                "POST",
                "/distribution/withdrawals",
                pa,
                Map.of("amountFen", 1000),
                UUID.randomUUID().toString(),
                409);
    }

    @Test
    void nextMaturityRepaysDebtAndConfiguredLimitsDoNotSplitRequests() throws Exception {
        String pa = freshPromoter(), source = paidSource(pa);
        String id =
                write("/distribution/withdrawals", pa, Map.of("amountFen", 8000))
                        .path("id")
                        .asText();
        write("/dev/withdrawals/" + id + "/simulate", pa, Map.of("scenario", "SUCCESS"));
        jdbc.update("update store_order set refunded_goods_fen=32000 where id=?", source);
        events.handle(
                "REFUND_SUCCEEDED",
                "debt-new-income",
                json.writeValueAsString(Map.of("orderId", source)));
        assertThat(call("GET", "/distribution/summary", pa, null, 200).path("debtFen").asLong())
                .isEqualTo(2000);
        paidSource(pa);
        var balance = call("GET", "/distribution/summary", pa, null, 200);
        assertThat(balance.path("debtFen").asLong()).isZero();
        assertThat(balance.path("availableFen").asLong()).isEqualTo(8000);
        assertThat(balance.path("paidOutFen").asLong()).isEqualTo(8000);
        write(
                "POST",
                "/distribution/withdrawals",
                pa,
                Map.of("amountFen", 99),
                UUID.randomUUID().toString(),
                400);
        write(
                "POST",
                "/distribution/withdrawals",
                pa,
                Map.of("amountFen", 100001),
                UUID.randomUUID().toString(),
                400);
        for (int n = 0; n < 4; n++)
            write("/distribution/withdrawals", pa, Map.of("amountFen", 100));
        write(
                "POST",
                "/distribution/withdrawals",
                pa,
                Map.of("amountFen", 100),
                UUID.randomUUID().toString(),
                409);
    }
}
