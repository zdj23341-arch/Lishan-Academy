package com.wechat.jxsc.payment;

import com.wechat.jxsc.auth.TokenCodec;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.order.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Clock;
import java.util.*;

@Service
public class PaymentInbox {
    @PersistenceContext private EntityManager em;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.ClosedAccountPrivacy privacy;

    private final SensitiveCipher cipher;
    private final CommerceSupport s;
    private final OrderService orders;
    private final PaymentTransactions payments;
    private final RefundFinalizer refunds;
    private final Clock clock;
    private final FinancialReconciliationService reconciliation;
    private final com.wechat.jxsc.common.tasks.TaskQueue tasks;

    public PaymentInbox(
            SensitiveCipher cipher,
            CommerceSupport s,
            OrderService orders,
            PaymentTransactions payments,
            RefundFinalizer refunds,
            Clock clock,
            com.wechat.jxsc.common.tasks.TaskQueue tasks,
            FinancialReconciliationService reconciliation) {
        this.cipher = cipher;
        this.s = s;
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
        this.clock = clock;
        this.tasks = tasks;
        this.reconciliation = reconciliation;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long record(PaymentProvider.VerifiedEvent e) {
        String encoded = s.encode(e);
        String hash = TokenCodec.hash(encoded);
        em.createNativeQuery(
                        "INSERT INTO"
                            + " callback_inbox(provider,event_id,resource_type,signature_valid,payload_encrypted,payload_hash,status,retry_count,created_at,updated_at,version)"
                            + " VALUES"
                            + " (:p,:e,:r,:v,:b,:h,'RECEIVED',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)"
                            + " ON DUPLICATE KEY UPDATE id=id")
                .setParameter("p", e.provider())
                .setParameter("e", e.eventId())
                .setParameter("r", e.resourceType())
                .setParameter("v", e.signatureValid())
                .setParameter("b", cipher.encrypt(encoded))
                .setParameter("h", hash)
                .executeUpdate();
        var row =
                em.createQuery(
                                "select b from CallbackInbox b where b.provider=:p and"
                                        + " b.eventId=:e",
                                CallbackInbox.class)
                        .setParameter("p", e.provider())
                        .setParameter("e", e.eventId())
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getSingleResult();
        if (!row.payloadHash.equals(hash))
            throw CommerceSupport.conflict(
                    "EVENT_CONFLICT", "Event identity was reused with changed fields");
        tasks.enqueue("INBOX_PROCESS", row.getId().toString(), "{}", clock.instant());
        return row.getId();
    }

    @Transactional(readOnly = true)
    public PaymentProvider.VerifiedEvent event(long id) {
        var b = em.find(CallbackInbox.class, id);
        try {
            return s.mapper()
                    .readValue(
                            cipher.decrypt(b.payloadEncrypted),
                            PaymentProvider.VerifiedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored event", e);
        }
    }

    @Transactional
    public String process(long inboxId, PaymentProvider.VerifiedEvent e) {
        return processInternal(inboxId, e, false);
    }

    @Transactional(readOnly = true)
    public boolean awaitingPaymentQuery(long inboxId) {
        var row = em.find(CallbackInbox.class, inboxId);
        return row != null
                && row.resourceType.equals("PAYMENT")
                && row.status.equals("RECONCILING");
    }

    @Transactional
    public boolean confirmPaymentQuery(
            long inboxId, PaymentProvider.Intent intent, PaymentProvider.Result result) {
        var e = event(inboxId);
        if (!e.resourceType().equals("PAYMENT")
                || e.transactionId() != intent.id()
                || e.orderId() != intent.orderId())
            throw CommerceSupport.conflict(
                    "EVENT_CONFLICT", "Query does not belong to stored payment evidence");
        orders.lock(e.orderId());
        var b = s.lock(CallbackInbox.class, inboxId);
        if (!b.status.equals("RECONCILING")) return true;
        var issue = reconciliation.find("PAYMENT", e.transactionId());
        if (issue == null)
            throw new IllegalStateException("Missing durable payment reconciliation");
        boolean terminal = reconciliation.query(issue, intent, result);
        if (terminal && issue.lastQueryState.equals("SUCCEEDED")) {
            String error = processInternal(inboxId, e, true);
            if (error == null) issue.status = "RESOLVED";
        }
        return terminal;
    }

    private String processInternal(
            long inboxId, PaymentProvider.VerifiedEvent e, boolean queryConfirmed) {
        var evidence = em.find(CallbackInbox.class, inboxId);
        if (evidence == null || !evidence.payloadHash.equals(TokenCodec.hash(s.encode(e))))
            throw CommerceSupport.conflict("EVENT_CONFLICT", "Event differs from stored evidence");
        String earlyError = !e.signatureValid() ? "INVALID_SIGNATURE" : null;
        if (earlyError == null && em.find(StoreOrder.class, e.orderId()) == null)
            earlyError = "ORDER_MISMATCH";
        if (earlyError == null
                && (e.resourceType().equals("PAYMENT")
                        ? em.find(PaymentTransaction.class, e.transactionId()) == null
                        : e.resourceType().equals("REFUND")
                                ? em.find(RefundTransaction.class, e.transactionId()) == null
                                : true)) earlyError = "RESOURCE_MISMATCH";
        if (earlyError != null) {
            var rejected = s.lock(CallbackInbox.class, inboxId);
            if (rejected.status.equals("REJECTED")) return rejected.errorCode;
            rejected.status = "REJECTED";
            rejected.errorCode = earlyError;
            rejected.processedAt = clock.instant();
            rejected.retryCount++;
            s.systemAudit(
                    "PAYMENT_EVENT_REJECTED",
                    "CALLBACK",
                    inboxId,
                    "Callback signature or resource validation failed",
                    Map.of("errorCode", earlyError));
            return earlyError;
        }
        var o = orders.lock(e.orderId());
        var b = s.lock(CallbackInbox.class, inboxId);
        if (b.status.equals("PROCESSED")) return null;
        if (b.status.equals("REJECTED")) return b.errorCode;
        String error = null;
        if (!e.signatureValid()) error = "INVALID_SIGNATURE";
        if (e.resourceType().equals("PAYMENT")) {
            var p = s.lock(PaymentTransaction.class, e.transactionId());
            if (!p.orderId.equals(o.getId())
                    || !p.channel.equals(e.provider())
                    || !p.outTradeNo.equals(e.businessNo())
                    || p.amountFen != e.amountFen()
                    || !e.currency().equals("CNY")
                    || !Objects.equals(e.appId(), p.appId)
                    || !Objects.equals(e.merchantId(), p.merchantId)
                    || e.channelId() == null
                    || e.channelId().isBlank()) error = "PAYMENT_MISMATCH";
            if (error == null && e.state() == PaymentProvider.State.SUCCEEDED) {
                if (p.channelTxnId != null && !p.channelTxnId.equals(e.channelId()))
                    error = "PAYMENT_MISMATCH";
                else {
                    if (!queryConfirmed
                            && o.orderStatus.equals("CANCELLED")
                            && !p.refundRequired
                            && o.successfulPaymentId == null) {
                        reconciliation.latePayment(o, p, inboxId, e.channelId());
                        p.channelTxnId = e.channelId();
                        b.status = "RECONCILING";
                        b.retryCount++;
                        return null;
                    }
                    p.channelTxnId = e.channelId();
                    p.state = "SUCCEEDED";
                    if (p.paidAt == null) p.paidAt = clock.instant();
                    if (!p.refundRequired
                            && !Objects.equals(o.successfulPaymentId, p.getId())
                            && privacy.orderClosedCurrent(o.getId()))
                        payments.exceptionalRefund(
                                o, p, "Late payment belongs to a closed account");
                    else if (!p.refundRequired
                            && o.successfulPaymentId != null
                            && !o.successfulPaymentId.equals(p.getId()))
                        payments.exceptionalRefund(o, p, "Additional successful payment");
                    else if (!p.refundRequired && o.successfulPaymentId == null) {
                        try {
                            orders.markPaid(o, p.getId());
                        } catch (com.wechat.jxsc.common.ApiException ex) {
                            if (!ex.getCode().equals("INSUFFICIENT_STOCK")) throw ex;
                            payments.exceptionalRefund(o, p, "Late payment cannot be fulfilled");
                        }
                    }
                }
            }
        } else if (e.resourceType().equals("REFUND")) {
            error = error == null ? refunds.apply(o, e, inboxId) : error;
        } else error = "EVENT_TYPE_MISMATCH";
        b.retryCount++;
        b.processedAt = clock.instant();
        b.errorCode = error;
        b.status = error == null ? "PROCESSED" : "REJECTED";
        if (error != null)
            s.systemAudit(
                    "PAYMENT_EVENT_REJECTED",
                    "CALLBACK",
                    b.getId(),
                    "Verified event did not match local financial intent",
                    Map.of("errorCode", error));
        return error;
    }
}
