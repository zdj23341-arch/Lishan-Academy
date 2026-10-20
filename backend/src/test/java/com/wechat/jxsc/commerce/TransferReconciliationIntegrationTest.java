package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.distribution.WithdrawalWorkflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

class TransferReconciliationIntegrationTest extends DistributionTestSupport {
    @Test
    void auditedCorrectionPreservesOriginalFailureAndFrozenNewerMoney() throws Exception {
        String p = funded(), old = request(p, 6000);
        simulate(p, old, "FAILURE");
        String newer = request(p, 8000);
        simulate(p, newer, "TIMEOUT");
        simulate(p, old, "SUCCESS_CALLBACK");
        worker.run(100);
        var reason =
                Map.of(
                        "reason",
                        "Authenticated original query confirms previously released payout");
        write("/admin/withdrawals/" + old + "/reconcile", a, reason);
        write("/admin/withdrawals/" + old + "/reconcile", a, reason);
        var summary = call("GET", "/distribution/summary", p, null, 200);
        assertThat(summary.path("paidOutFen").asLong()).isEqualTo(6000);
        assertThat(summary.path("availableFen").asLong()).isZero();
        assertThat(summary.path("debtFen").asLong()).isEqualTo(4000);
        assertThat(summary.path("withdrawingFen").asLong()).isEqualTo(8000);
        assertThat(call("GET", "/admin/withdrawals/" + old, a, null, 200).path("status").asText())
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " business_key=?",
                                Integer.class,
                                "withdraw-correction:" + old))
                .isEqualTo(1);
        simulate(p, old, "SUCCESS_CALLBACK");
        assertThat(
                        call("GET", "/admin/withdrawals/" + old, a, null, 200)
                                .path("reconciliation")
                                .path("status")
                                .asText())
                .isEqualTo("RESOLVED");
    }

    @Autowired WithdrawalWorkflow workflow;

    String funded() throws Exception {
        String p = freshPromoter();
        jdbc.update(
                "update commission_account set available_fen=10000 where distributor_id=?", did(p));
        return p;
    }

    String request(String p, int amount) throws Exception {
        return write("/distribution/withdrawals", p, Map.of("amountFen", amount))
                .path("id")
                .asText();
    }

    void simulate(String p, String id, String state) throws Exception {
        write("/dev/withdrawals/" + id + "/simulate", p, Map.of("scenario", state));
    }

    @Test
    void contradictorySuccessHoldsNewUnsentRequestAcrossRiskClearAndQueriesOriginal()
            throws Exception {
        String p = funded(), old = request(p, 1000);
        simulate(p, old, "FAILURE");
        String next = request(p, 8000);
        simulate(p, old, "SUCCESS_CALLBACK");
        var held = call("GET", "/admin/withdrawals/" + old, a, null, 200);
        assertThat(held.path("reconciliation").path("observedPaidOutFen").asLong()).isEqualTo(1000);
        assertThat(held.path("reconciliation").path("accountedPaidOutFen").asLong()).isZero();
        long version =
                jdbc.queryForObject(
                        "select version from distributor where id=?", Long.class, did(p));
        call(
                "PATCH",
                "/admin/distributors/" + did(p),
                a,
                Map.of(
                        "version",
                        version,
                        "status",
                        "ACTIVE",
                        "riskBlocked",
                        false,
                        "reason",
                        "Clear ordinary risk only"),
                200);
        write(
                "POST",
                "/distribution/withdrawals",
                p,
                Map.of("amountFen", 100),
                UUID.randomUUID().toString(),
                409);
        assertThatThrownBy(() -> workflow.run(Long.parseLong(next)))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select dispatch_started_at from withdrawal_request where id=?",
                                Object.class,
                                next))
                .isNull();
        worker.run(100);
        var reconciled = call("GET", "/admin/withdrawals/" + old, a, null, 200);
        assertThat(reconciled.path("reconciliation").path("status").asText())
                .isEqualTo("NEEDS_REVIEW");
        assertThat(reconciled.path("status").asText()).isEqualTo("FAILED");
        assertThat(
                        call("GET", "/distribution/summary", p, null, 200)
                                .path("withdrawalEnabled")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void newerAlreadyDispatchedTransferCanFinishWhileOldConflictRemainsHeld() throws Exception {
        String p = funded(), old = request(p, 1000);
        simulate(p, old, "FAILURE");
        String next = request(p, 8000);
        simulate(p, next, "TIMEOUT");
        simulate(p, old, "SUCCESS_CALLBACK");
        simulate(p, next, "SUCCESS_QUERY");
        assertThat(
                        call("GET", "/distribution/withdrawals/" + next, p, null, 200)
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        var summary = call("GET", "/distribution/summary", p, null, 200);
        assertThat(summary.path("paidOutFen").asLong()).isEqualTo(8000);
        assertThat(summary.path("withdrawalEnabled").asBoolean()).isFalse();
        assertThat(summary.path("observedUnaccountedPaidOutFen").asLong()).isEqualTo(1000);
    }
}
