package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.tasks.TaskHandler;
import com.wechat.jxsc.distribution.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;

class FinanceSafetyIntegrationTest extends DistributionTestSupport {
    @Autowired CommissionService commissions;
    @Autowired WithdrawalWorkflow withdrawals;
    @Autowired WithdrawalTransactions transfers;

    String source(String pa, long price) throws Exception {
        String p = book(a, 5, price);
        rule(p, did(pa));
        touch(pa, p);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", id, "{}");
        return id;
    }

    void complete(String id) {
        jdbc.update(
                "update store_order set order_status='COMPLETED',completed_at=? where id=?",
                Timestamp.from(clock.now.minusSeconds(7 * 86400L)),
                id);
        events.handle("ORDER_COMPLETED", id, "{}");
    }

    @Test
    void sourceHoldBlocksMaturityAndSpendingPreviouslyMaturedFunds() throws Exception {
        String pa = freshPromoter(), id = source(pa, 80000);
        complete(id);
        commissions.mature(Long.parseLong(id));
        assertThat(
                        call("GET", "/distribution/summary", pa, null, 200)
                                .path("availableFen")
                                .asLong())
                .isEqualTo(10000);
        jdbc.update("update store_order set financial_hold=true where id=?", id);
        write(
                "POST",
                "/distribution/withdrawals",
                pa,
                Map.of("amountFen", 8000),
                UUID.randomUUID().toString(),
                409);
        assertThat(
                        call("GET", "/distribution/summary", pa, null, 200)
                                .path("disabledReason")
                                .asText())
                .isEqualTo("FINANCIAL_HOLD");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from withdrawal_request where distributor_id=?",
                                Long.class,
                                did(pa)))
                .isZero();
        String pb = freshPromoter(), pending = source(pb, 9990);
        complete(pending);
        jdbc.update("update store_order set financial_hold=true where id=?", pending);
        assertThatThrownBy(() -> commissions.mature(Long.parseLong(pending)))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(call("GET", "/distribution/summary", pb, null, 200).path("pendingFen").asLong())
                .isEqualTo(1248);
    }

    @Test
    void refundDuringTransferCreatesDebtAndFailureReleaseRepaysDebtFirst() throws Exception {
        String pa = freshPromoter(), id = source(pa, 80000);
        complete(id);
        commissions.mature(Long.parseLong(id));
        String wid =
                write("/distribution/withdrawals", pa, Map.of("amountFen", 8000))
                        .path("id")
                        .asText();
        write("/dev/withdrawals/" + wid + "/simulate", pa, Map.of("scenario", "TIMEOUT"));
        jdbc.update("update store_order set refunded_goods_fen=32000 where id=?", id);
        events.handle(
                "REFUND_SUCCEEDED", "debt-fixture", json.writeValueAsString(Map.of("orderId", id)));
        var debt = call("GET", "/distribution/summary", pa, null, 200);
        assertThat(debt.path("availableFen").asLong()).isZero();
        assertThat(debt.path("withdrawingFen").asLong()).isEqualTo(8000);
        assertThat(debt.path("debtFen").asLong()).isEqualTo(2000);
        write("/dev/withdrawals/" + wid + "/simulate", pa, Map.of("scenario", "FAILURE_CALLBACK"));
        write("/dev/withdrawals/" + wid + "/simulate", pa, Map.of("scenario", "FAILURE_CALLBACK"));
        var released = call("GET", "/distribution/summary", pa, null, 200);
        assertThat(released.path("availableFen").asLong()).isEqualTo(6000);
        assertThat(released.path("debtFen").asLong()).isZero();
        assertThat(released.path("withdrawingFen").asLong()).isZero();
    }

    @Test
    void ordinaryRequestRunsAutomaticallyAndConfirmationIsProtected() throws Exception {
        String pa = freshPromoter(), id = source(pa, 80000);
        complete(id);
        commissions.mature(Long.parseLong(id));
        String wid =
                write("/distribution/withdrawals", pa, Map.of("amountFen", 2000))
                        .path("id")
                        .asText();
        withdrawals.run(Long.parseLong(wid));
        assertThat(
                        call("GET", "/distribution/withdrawals/" + wid, pa, null, 200)
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        String confirm =
                write("/distribution/withdrawals", pa, Map.of("amountFen", 2000))
                        .path("id")
                        .asText();
        write("/dev/withdrawals/" + confirm + "/simulate", pa, Map.of("scenario", "USER_CONFIRM"));
        var owned = call("GET", "/distribution/withdrawals/" + confirm, pa, null, 200);
        assertThat(owned.path("confirmation").path("packageInfo").asText())
                .isEqualTo("DEV-PACKAGE-" + confirm);
        assertThat(
                        call("GET", "/admin/withdrawals/" + confirm, a, null, 200)
                                .path("confirmation")
                                .isNull())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select confirmation_cipher from withdrawal_request where id=?",
                                String.class,
                                confirm))
                .doesNotContain("DEV-PACKAGE");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from idempotency_record where response_json like"
                                        + " '%DEV-PACKAGE%'",
                                Long.class))
                .isZero();
        write(
                "/dev/withdrawals/" + confirm + "/simulate",
                pa,
                Map.of("scenario", "CONFIRM_RECEIPT"));
        assertThat(call("GET", "/distribution/summary", pa, null, 200).path("paidOutFen").asLong())
                .isEqualTo(4000);
    }

    @Test
    void originalBillQueryRecoversTimeoutAndAlertsAfterThirtyMinutes() throws Exception {
        String pa = freshPromoter(), id = source(pa, 80000);
        complete(id);
        commissions.mature(Long.parseLong(id));
        var w = write("/distribution/withdrawals", pa, Map.of("amountFen", 2000));
        String wid = w.path("id").asText();
        write("/dev/withdrawals/" + wid + "/simulate", pa, Map.of("scenario", "TIMEOUT"));
        clock.now = clock.now.plusSeconds(1801);
        assertThatThrownBy(() -> withdrawals.run(Long.parseLong(wid)))
                .isInstanceOf(TaskHandler.RetryLater.class);
        assertThat(
                        call("GET", "/distribution/withdrawals/" + wid, pa, null, 200)
                                .path("alerted")
                                .asBoolean())
                .isTrue();
        write("/dev/withdrawals/" + wid + "/simulate", pa, Map.of("scenario", "SUCCESS_QUERY"));
        var done = call("GET", "/distribution/withdrawals/" + wid, pa, null, 200);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("outBillNo")).isEqualTo(w.path("outBillNo"));
        assertThat(
                        jdbc.queryForObject(
                                "select send_count from transfer_simulation where withdrawal_id=?",
                                Integer.class,
                                wid))
                .isEqualTo(1);
    }
}
