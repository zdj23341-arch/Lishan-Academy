package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Orchestrates short database transactions around provider I/O. Never annotate this class
 * transactional.
 */
@Service
public class PaymentWorkflow implements TaskHandler {
    private final PaymentProvider provider;
    private final PaymentTransactions tx;
    private final PaymentInbox inbox;
    private final RefundTransactions refundTx;
    private final OrderService orders;

    public PaymentWorkflow(
            PaymentProvider provider,
            PaymentTransactions tx,
            PaymentInbox inbox,
            RefundTransactions refundTx,
            OrderService orders) {
        this.provider = provider;
        this.tx = tx;
        this.inbox = inbox;
        this.refundTx = refundTx;
        this.orders = orders;
    }

    public PaymentDtos.View prepay(long orderId, String key) {
        var i = tx.prepare(orderId, key, false);
        ensureChannel(i);
        if (tx.closeUnsent(i)) return tx.view(i.id());
        PaymentProvider.Result result;
        try {
            result =
                    !i.channel().equals("WECHAT_PAY") || tx.startDispatch(i)
                            ? provider.prepay(i)
                            : provider.query(i);
        } catch (RuntimeException e) {
            tx.applyResult(i, unknown(i));
            throw e;
        }
        apply(i, result, "PAYMENT", false);
        return tx.view(i.id());
    }

    public OrderDtos.View cancel(long id, String key) {
        var p = tx.cancel(id, key, false);
        if (p != null) syncPayment(p);
        return orders.get(id, false);
    }

    public void syncPayment(long id) {
        var i = tx.payment(id);
        ensureChannel(i);
        if (tx.closeUnsent(i)) return;
        PaymentProvider.Result result;
        try {
            result =
                    i.channel().equals("WECHAT_PAY") && tx.startDispatch(i)
                            ? provider.prepay(i)
                            : provider.query(i);
            if (result.state() == PaymentProvider.State.PROCESSING
                    || result.state() == PaymentProvider.State.CREATED) {
                if (tx.cancellationRequested(i.orderId())) result = provider.close(i);
            }
        } catch (RuntimeException e) {
            tx.applyResult(i, unknown(i));
            throw e;
        }
        apply(i, result, "PAYMENT", false);
    }

    public void syncRefund(long id) {
        var i = tx.refund(id);
        ensureChannel(i);
        var action = refundTx.prepare(i);
        if (action == RefundTransactions.Action.STOP) return;
        if (action == RefundTransactions.Action.QUERY_HELD) {
            reconcileRefund(id);
            return;
        }
        PaymentProvider.Result result;
        try {
            result =
                    action == RefundTransactions.Action.SEND
                            ? provider.refund(i)
                            : provider.queryRefund(i);
        } catch (RuntimeException e) {
            refundTx.apply(i, unknown(i));
            throw e;
        }
        apply(i, result, "REFUND", false);
    }

    public void reconcileRefund(long id) {
        var intent = tx.refund(id);
        if (!refundTx.reconciliationPending(intent)) return;
        ensureChannel(intent);
        PaymentProvider.Result result;
        try {
            result = provider.queryRefund(intent);
        } catch (RuntimeException e) {
            refundTx.reconcile(intent, unknown(intent));
            throw e;
        }
        if (!refundTx.reconcile(intent, result))
            throw new RetryLater("REFUND_RECONCILIATION_PENDING", Duration.ofMinutes(5));
    }

    public void reconcilePayment(long inboxId) {
        if (!inbox.awaitingPaymentQuery(inboxId)) return;
        var event = inbox.event(inboxId);
        var intent = tx.payment(event.transactionId());
        ensureChannel(intent);
        PaymentProvider.Result result;
        try {
            result = provider.query(intent);
        } catch (RuntimeException e) {
            inbox.confirmPaymentQuery(inboxId, intent, unknown(intent));
            throw e;
        }
        if (!inbox.confirmPaymentQuery(inboxId, intent, result))
            throw new RetryLater("PAYMENT_RECONCILIATION_PENDING", Duration.ofSeconds(30));
    }

    public OrderDtos.View devPay(long id, String key, String scenario) {
        if (!(provider instanceof DevPaymentProvider dev)) throw CommerceSupport.missing();
        var i = tx.prepareDev(id, key, scenario);
        boolean mismatch = scenario.equals("AMOUNT_MISMATCH");
        if (!mismatch)
            dev.scenario(i, "PAYMENT", scenario.equals("EXTRA_SUCCESS") ? "SUCCESS" : scenario);
        var result = dev.query(i);
        if (mismatch) {
            result =
                    new PaymentProvider.Result(
                            PaymentProvider.State.SUCCEEDED,
                            result.channelId(),
                            i.amountFen() + 1,
                            result.currency(),
                            result.appId(),
                            result.merchantId(),
                            Map.of());
        }
        apply(i, result, "PAYMENT", mismatch);
        return orders.get(id, false);
    }

    public void devRefund(long id, String key, String scenario) {
        tx.registerRefundScenario(id, key, scenario);
        if (!(provider instanceof DevPaymentProvider dev)) throw CommerceSupport.missing();
        var i = tx.refund(id);
        ensureChannel(i);
        dev.scenario(i, "REFUND", scenario);
        syncRefund(id);
    }

    private void ensureChannel(PaymentProvider.Intent i) {
        if (!provider.channel().equals(i.channel()))
            throw CommerceSupport.conflict(
                    "INVALID_CHANNEL", "Provider cannot process this order channel");
    }

    private PaymentProvider.Result unknown(PaymentProvider.Intent i) {
        return new PaymentProvider.Result(
                PaymentProvider.State.UNKNOWN,
                null,
                i.amountFen(),
                i.currency(),
                i.appId(),
                i.merchantId(),
                Map.of());
    }

    private void apply(
            PaymentProvider.Intent i, PaymentProvider.Result r, String kind, boolean mismatch) {
        boolean invalidIdentity =
                r.amountFen() != i.amountFen()
                        || !Objects.equals(r.currency(), i.currency())
                        || !Objects.equals(r.appId(), i.appId())
                        || !Objects.equals(r.merchantId(), i.merchantId());
        if (r.state() == PaymentProvider.State.SUCCEEDED || invalidIdentity) {
            var e =
                    new PaymentProvider.VerifiedEvent(
                            i.channel(),
                            kind
                                    + ":"
                                    + i.businessNo()
                                    + ":"
                                    + (mismatch || invalidIdentity
                                            ? "MISMATCH-" + r.state()
                                            : "SUCCESS"),
                            kind,
                            i.id(),
                            i.orderId(),
                            i.businessNo(),
                            r.channelId(),
                            r.amountFen(),
                            r.currency(),
                            r.appId(),
                            r.merchantId(),
                            r.state(),
                            true,
                            "Provider-verified " + kind + " result");
            long row = inbox.record(e);
            String error = inbox.process(row, e);
            if (error != null) {
                if (kind.equals("PAYMENT")) tx.applyResult(i, unknown(i));
                else refundTx.apply(i, unknown(i));
                throw CommerceSupport.conflict(error, "Payment event does not match server intent");
            }
            if (kind.equals("PAYMENT") && inbox.awaitingPaymentQuery(row)) reconcilePayment(row);
        } else if (kind.equals("PAYMENT")) tx.applyResult(i, r);
        else refundTx.apply(i, r);
    }

    public boolean supports(String type) {
        return Set.of(
                        "PAYMENT_SYNC",
                        "PAYMENT_CLOSE",
                        "ORDER_EXPIRE",
                        "REFUND_SYNC",
                        "REFUND_RECONCILE",
                        "PAYMENT_RECONCILE",
                        "INBOX_PROCESS")
                .contains(type);
    }

    public void handle(String type, String key, String payload) {
        long id = Long.parseLong(key);
        switch (type) {
            case "REFUND_RECONCILE" -> reconcileRefund(id);
            case "PAYMENT_RECONCILE" -> reconcilePayment(id);
            case "ORDER_EXPIRE" -> {
                Long payment = tx.cancel(id, null, true);
                if (payment != null) {
                    syncPayment(payment);
                    if (tx.cancellationRequested(id))
                        throw new RetryLater("PAYMENT_PROCESSING", Duration.ofSeconds(30));
                }
            }
            case "REFUND_SYNC" -> {
                syncRefund(id);
                if (refundTx.ordinaryPending(id))
                    throw new RetryLater("REFUND_PROCESSING", Duration.ofSeconds(30));
            }
            case "INBOX_PROCESS" -> {
                var e = inbox.event(id);
                inbox.process(id, e);
            }
            default -> {
                syncPayment(id);
                if (Set.of("CREATED", "PROCESSING", "UNKNOWN").contains(tx.view(id).state()))
                    throw new RetryLater("PAYMENT_PROCESSING", Duration.ofSeconds(30));
            }
        }
    }
}
