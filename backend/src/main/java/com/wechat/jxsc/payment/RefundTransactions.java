package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

@Service
public class RefundTransactions {
    @PersistenceContext private EntityManager em;
    private final OrderService orders;
    private final CommerceSupport s;
    private final TaskQueue tasks;
    private final Clock clock;
    private final FinancialReconciliationService reconciliation;

    public RefundTransactions(
            OrderService orders,
            CommerceSupport s,
            TaskQueue tasks,
            Clock clock,
            FinancialReconciliationService reconciliation) {
        this.orders = orders;
        this.s = s;
        this.tasks = tasks;
        this.clock = clock;
        this.reconciliation = reconciliation;
    }

    @Transactional
    public String state(long id) {
        var r = em.find(RefundTransaction.class, id);
        if (r == null) throw CommerceSupport.missing();
        return r.state;
    }

    public enum Action {
        SEND,
        QUERY,
        QUERY_HELD,
        STOP
    }

    @Transactional(readOnly = true)
    public boolean ordinaryPending(long id) {
        var r = em.find(RefundTransaction.class, id);
        return !r.dispatchSuppressed
                && em.createNativeQuery(
                                "select id from economic_resolution where resource_type='REFUND'"
                                        + " and transaction_id=:id")
                        .setParameter("id", id)
                        .getResultList()
                        .isEmpty()
                && !em.find(StoreOrder.class, r.orderId).financialHold
                && Set.of("CREATED", "PROCESSING", "UNKNOWN").contains(r.state);
    }

    @Transactional
    public Action prepare(PaymentProvider.Intent intent) {
        var o = orders.lock(intent.orderId());
        var r = s.lock(RefundTransaction.class, intent.id());
        if (r.dispatchSuppressed
                || !em.createNativeQuery(
                                "select id from economic_resolution where resource_type='REFUND'"
                                        + " and transaction_id=:id")
                        .setParameter("id", r.getId())
                        .getResultList()
                        .isEmpty()) return Action.STOP;
        if (o.financialHold) {
            if (r.dispatchStartedAt != null
                    && !Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(r.state)) {
                var issue = reconciliation.trackInFlight(o, r);
                return issue.status.equals("PENDING_QUERY") ? Action.QUERY_HELD : Action.STOP;
            }
            var issue = reconciliation.find("REFUND", r.getId());
            if (issue != null)
                return issue.status.equals("PENDING_QUERY") ? Action.QUERY_HELD : Action.STOP;
            if (r.dispatchStartedAt == null)
                throw new TaskHandler.RetryLater(
                        "FINANCIAL_RECONCILIATION_REQUIRED", java.time.Duration.ofMinutes(5));
            return Action.STOP;
        }
        if (Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(r.state)) return Action.STOP;
        if (r.state.equals("CREATED") && r.dispatchStartedAt == null) {
            r.dispatchStartedAt = clock.instant();
            r.state = "UNKNOWN";
            return Action.SEND;
        }
        return Action.QUERY;
    }

    @Transactional
    public boolean reconciliationPending(PaymentProvider.Intent intent) {
        orders.lock(intent.orderId());
        var issue = reconciliation.find("REFUND", intent.id());
        return issue != null && issue.status.equals("PENDING_QUERY");
    }

    @Transactional
    public boolean reconcile(PaymentProvider.Intent intent, PaymentProvider.Result result) {
        orders.lock(intent.orderId());
        var r = s.lock(RefundTransaction.class, intent.id());
        var issue = reconciliation.find("REFUND", r.getId());
        if (issue == null) return true;
        // Never erase accounting or a case to make contradictory channel evidence fit.
        return reconciliation.query(issue, intent, result);
    }

    @Transactional
    public void apply(PaymentProvider.Intent intent, PaymentProvider.Result result) {
        var o = orders.lock(intent.orderId());
        var r = s.lock(RefundTransaction.class, intent.id());
        if (r.dispatchSuppressed
                || !em.createNativeQuery(
                                "select id from economic_resolution where resource_type='REFUND'"
                                        + " and transaction_id=:id")
                        .setParameter("id", r.getId())
                        .getResultList()
                        .isEmpty()) return;
        if (r.state.equals("SUCCEEDED") || r.state.equals("FAILED") || r.state.equals("CLOSED"))
            return;
        if (o.financialHold) {
            var issue = reconciliation.trackInFlight(o, r);
            reconciliation.recordObservation(issue, result, "DISPATCH_RESULT");
            return;
        }
        r.state = result.state().name();
        if ("REFUND_ABNORMAL".equals(result.params().get("channelException"))) {
            em.createNativeQuery(
                            "insert ignore into"
                                + " reconciliation_issue(channel,business_no,resource_type,issue_code,bill_date,local_amount_fen)"
                                + " values(:c,:b,'REFUND','REFUND_ABNORMAL',:d,:a)")
                    .setParameter("c", intent.channel())
                    .setParameter("b", intent.businessNo())
                    .setParameter(
                            "d",
                            java.time.LocalDate.ofInstant(
                                    clock.instant(), java.time.ZoneId.of("Asia/Shanghai")))
                    .setParameter("a", intent.amountFen())
                    .executeUpdate();
        }
        if ((r.state.equals("FAILED") || r.state.equals("CLOSED")) && !r.exceptional) {
            var a = s.lock(AftersaleCase.class, r.aftersaleId);
            a.status = "FAILED";
            if (Objects.equals(o.activeAftersaleId, a.getId())) {
                o.activeAftersaleId = null;
                o.refundStatus = "FAILED";
            }
            tasks.enqueue(
                    "AFTERSALE_CLOSED",
                    a.getId().toString(),
                    s.encode(Map.of("orderId", o.getId().toString())),
                    clock.instant());
            s.systemAudit(
                    "REFUND_FAILED",
                    "REFUND",
                    r.getId(),
                    "Provider confirmed refund failure",
                    Map.of("state", r.state));
        }
    }
}
