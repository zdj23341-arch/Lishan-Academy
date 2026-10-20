package com.wechat.jxsc.payment;

import com.wechat.jxsc.auth.TokenCodec;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.TaskQueue;
import com.wechat.jxsc.order.StoreOrder;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

/**
 * Called under the order lock. Observed channel money is separate from unsettled local accounting.
 */
@Service
public class FinancialReconciliationService {
    @PersistenceContext private EntityManager em;
    private final CommerceSupport s;
    private final SensitiveCipher cipher;
    private final TaskQueue tasks;
    private final Clock clock;

    public FinancialReconciliationService(
            CommerceSupport s, SensitiveCipher cipher, TaskQueue tasks, Clock clock) {
        this.s = s;
        this.cipher = cipher;
        this.tasks = tasks;
        this.clock = clock;
    }

    public FinancialReconciliation find(String type, long transactionId) {
        var ids =
                em.createNativeQuery(
                                "select id from financial_reconciliation where resource_type=:t and"
                                        + " transaction_id=:i for update")
                        .setParameter("t", type)
                        .setParameter("i", transactionId)
                        .getResultList();
        return ids.isEmpty()
                ? null
                : s.lock(FinancialReconciliation.class, ((Number) ids.getFirst()).longValue());
    }

    private FinancialReconciliation open(
            StoreOrder o,
            String type,
            long transactionId,
            Long inboxId,
            long amount,
            String reason) {
        // Upsert before lookup: never take an empty-range shared lock before inserting.
        em.createNativeQuery(
                        "insert into"
                            + " financial_reconciliation(order_id,resource_type,transaction_id,first_inbox_id,amount_fen,reason,status,last_query_state,success_observed,created_at,updated_at,version)"
                            + " values"
                            + " (:o,:t,:i,:b,:a,:r,'PENDING_QUERY','NOT_QUERIED',false,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)"
                            + " on duplicate key update id=id")
                .setParameter("o", o.getId())
                .setParameter("t", type)
                .setParameter("i", transactionId)
                .setParameter("b", inboxId)
                .setParameter("a", amount)
                .setParameter("r", reason)
                .executeUpdate();
        return find(type, transactionId);
    }

    public void latePayment(StoreOrder o, PaymentTransaction p, long inboxId, String channelId) {
        var issue = open(o, "PAYMENT", p.getId(), inboxId, p.amountFen, "LATE_PAYMENT_AFTER_CLOSE");
        issue.successObserved = true;
        issue.channelId = channelId;
        tasks.enqueue("PAYMENT_RECONCILE", Long.toString(inboxId), "{}", clock.instant());
    }

    public void observeRefund(
            StoreOrder o, RefundTransaction r, PaymentProvider.VerifiedEvent event, long inboxId) {
        o.financialHold = true;
        var issue =
                open(
                        o,
                        "REFUND",
                        r.getId(),
                        inboxId,
                        r.goodsFen + r.freightFen,
                        Set.of("FAILED", "CLOSED").contains(r.state)
                                ? "TERMINAL_REFUND_CONFLICT"
                                : "REFUND_ACCOUNTING_HOLD");
        issue.successObserved = true;
        issue.channelId = event.channelId();
        if (issue.firstInboxId == null) issue.firstInboxId = inboxId;
        tasks.enqueue("REFUND_RECONCILE", r.getId().toString(), "{}", clock.instant());
        // These sends cannot be revoked. Preserve and query each independently of the conflicting
        // refund.
        var ids =
                em.createNativeQuery(
                                "select id from refund_transaction where order_id=:o and"
                                        + " dispatch_started_at is not null and state not in"
                                        + " ('SUCCEEDED','FAILED','CLOSED') for update")
                        .setParameter("o", o.getId())
                        .getResultList();
        for (Object id : ids)
            trackInFlight(o, s.lock(RefundTransaction.class, ((Number) id).longValue()));
        s.systemAudit(
                "REFUND_RECONCILIATION_REQUIRED",
                "REFUND",
                r.getId(),
                "Verified monetary success requires reconciliation",
                Map.of("amountFen", issue.amountFen, "reason", issue.reason));
    }

    public FinancialReconciliation trackInFlight(StoreOrder o, RefundTransaction r) {
        var issue =
                open(
                        o,
                        "REFUND",
                        r.getId(),
                        null,
                        r.goodsFen + r.freightFen,
                        "HELD_IN_FLIGHT_REFUND");
        tasks.enqueue("REFUND_RECONCILE", r.getId().toString(), "{}", clock.instant());
        return issue;
    }

    public boolean query(
            FinancialReconciliation issue,
            PaymentProvider.Intent intent,
            PaymentProvider.Result result) {
        recordObservation(issue, result, "QUERY");
        if (issue.status.equals("RESOLVED")) return true;
        boolean valid =
                result.amountFen() == intent.amountFen()
                        && Objects.equals(result.currency(), intent.currency())
                        && Objects.equals(result.appId(), intent.appId())
                        && Objects.equals(result.merchantId(), intent.merchantId())
                        && (result.state() != PaymentProvider.State.SUCCEEDED
                                || result.channelId() != null
                                        && !result.channelId().isBlank()
                                        && (issue.channelId == null
                                                || issue.channelId.equals(result.channelId())));
        issue.lastQueryState = valid ? result.state().name() : "IDENTITY_MISMATCH";
        if (valid && result.state() == PaymentProvider.State.SUCCEEDED) {
            issue.successObserved = true;
            issue.channelId = result.channelId();
        }
        boolean terminal =
                valid
                        && Set.of(
                                        PaymentProvider.State.SUCCEEDED,
                                        PaymentProvider.State.CLOSED,
                                        PaymentProvider.State.FAILED)
                                .contains(result.state());
        issue.status = terminal ? "NEEDS_REVIEW" : "PENDING_QUERY";
        return terminal;
    }

    public void recordObservation(
            FinancialReconciliation issue, PaymentProvider.Result result, String source) {
        String encoded = s.encode(result);
        em.createNativeQuery(
                        "insert ignore into"
                            + " reconciliation_query(reconciliation_id,source,payload_encrypted,payload_hash,created_at)"
                            + " values (:i,:s,:p,:h,UTC_TIMESTAMP(6))")
                .setParameter("i", issue.getId())
                .setParameter("s", source)
                .setParameter("p", cipher.encrypt(encoded))
                .setParameter("h", TokenCodec.hash(source + ":" + encoded))
                .executeUpdate();
    }
}
