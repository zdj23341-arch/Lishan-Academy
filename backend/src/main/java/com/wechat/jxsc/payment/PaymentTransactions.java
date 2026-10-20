package com.wechat.jxsc.payment;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

@Service
public class PaymentTransactions {
    @PersistenceContext private EntityManager em;
    private final OrderService orders;
    private final CommerceSupport s;
    private final TaskQueue tasks;
    private final Clock clock;
    private final org.springframework.core.env.Environment environment;
    private final SensitiveCipher cipher;
    private final com.wechat.jxsc.payment.wechat.WechatSettings wechat;

    public PaymentTransactions(
            OrderService orders,
            CommerceSupport s,
            TaskQueue tasks,
            Clock clock,
            org.springframework.core.env.Environment environment,
            SensitiveCipher cipher,
            com.wechat.jxsc.payment.wechat.WechatSettings wechat) {
        this.orders = orders;
        this.s = s;
        this.tasks = tasks;
        this.clock = clock;
        this.environment = environment;
        this.cipher = cipher;
        this.wechat = wechat;
    }

    @Transactional
    public PaymentProvider.Intent prepare(long orderId, String key, boolean dev) {
        var response =
                s.execute(
                        "PREPAY",
                        key,
                        Map.of("orderId", Long.toString(orderId)),
                        () -> {
                            var o = orders.lock(orderId);
                            CurrentActor.require().requireOwner(o.userId);
                            if (dev && !o.channel.equals("DEV"))
                                throw CommerceSupport.conflict(
                                        "INVALID_CHANNEL", "DEV payment requires a DEV order");
                            if (o.channel.equals("NO_CHARGE"))
                                throw CommerceSupport.conflict(
                                        "NO_CHARGE_ORDER", "Order completed without a charge");
                            if (o.successfulPaymentId != null)
                                return Map.of("id", o.successfulPaymentId.toString());
                            if (dev) {
                                var existing =
                                        em.createQuery(
                                                        "select p from PaymentTransaction p where"
                                                            + " p.orderId=:o order by p.id desc",
                                                        PaymentTransaction.class)
                                                .setParameter("o", orderId)
                                                .setMaxResults(1)
                                                .getResultList();
                                if (!existing.isEmpty())
                                    return Map.of("id", existing.getFirst().getId().toString());
                            }
                            if (!o.orderStatus.equals("PENDING_PAYMENT")
                                    || o.cancelRequested
                                    || !o.expiresAt.isAfter(clock.instant()))
                                throw CommerceSupport.conflict(
                                        "INVALID_STATE", "Order no longer accepts payment");
                            var rows =
                                    em.createQuery(
                                                    "select p from PaymentTransaction p where"
                                                            + " p.orderId=:o and p.state in"
                                                            + " ('CREATED','PROCESSING','UNKNOWN')",
                                                    PaymentTransaction.class)
                                            .setParameter("o", orderId)
                                            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                                            .getResultList();
                            PaymentTransaction p;
                            if (rows.isEmpty()) {
                                p = new PaymentTransaction();
                                p.orderId = orderId;
                                p.outTradeNo =
                                        o.channel.equals("DEV")
                                                ? "DEV-P-" + UUID.randomUUID()
                                                : UUID.randomUUID().toString().replace("-", "");
                                p.channel = o.channel;
                                identity(p);
                                if (!p.channel.equals("DEV")) {
                                    var item = orders.item(orderId);
                                    p.productPath = wechat.paymentPath(item.type);
                                    p.description =
                                            item.titleSnapshot.substring(
                                                    0, Math.min(40, item.titleSnapshot.length()));
                                    var identities =
                                            em.createNativeQuery(
                                                            "select openid from"
                                                                    + " user_wechat_identity where"
                                                                    + " user_id=:u and app_id=:a")
                                                    .setParameter("u", o.userId)
                                                    .setParameter("a", p.appId)
                                                    .getResultList();
                                    if (identities.size() != 1)
                                        throw com.wechat.jxsc.payment.wechat.WechatProtocol
                                                .unavailable("WECHAT_PAYER_NOT_BOUND");
                                    p.payerCipher = cipher.encrypt((String) identities.getFirst());
                                }
                                p.state = "CREATED";
                                p.amountFen = o.payableAmountFen;
                                em.persist(p);
                                em.flush();
                                tasks.enqueue(
                                        "PAYMENT_SYNC",
                                        p.getId().toString(),
                                        "{}",
                                        clock.instant());
                            } else p = rows.getFirst();
                            return Map.of("id", p.getId().toString());
                        });
        return intent(
                em.find(PaymentTransaction.class, Long.parseLong(response.path("id").asText())));
    }

    @Transactional(readOnly = true)
    public PaymentProvider.Intent payment(long id) {
        var p = em.find(PaymentTransaction.class, id);
        if (p == null) throw CommerceSupport.missing();
        return intent(p);
    }

    @Transactional(readOnly = true)
    public PaymentProvider.Intent refund(long id) {
        var r = em.find(RefundTransaction.class, id);
        if (r == null) throw CommerceSupport.missing();
        var p = em.find(PaymentTransaction.class, r.paymentId);
        return new PaymentProvider.Intent(
                r.getId(),
                r.orderId,
                r.outRefundNo,
                r.channel,
                r.goodsFen + r.freightFen,
                "CNY",
                p.appId,
                p.merchantId,
                p.outTradeNo,
                p.channelTxnId,
                p.amountFen);
    }

    private PaymentProvider.Intent intent(PaymentTransaction p) {
        return new PaymentProvider.Intent(
                p.getId(),
                p.orderId,
                p.outTradeNo,
                p.channel,
                p.amountFen,
                "CNY",
                p.appId,
                p.merchantId,
                null,
                null,
                null,
                cipher.decrypt(p.payerCipher),
                p.description,
                p.productPath);
    }

    @Transactional
    public boolean startDispatch(PaymentProvider.Intent i) {
        orders.lock(i.orderId());
        var p = s.lock(PaymentTransaction.class, i.id());
        if (p.dispatchStartedAt != null || !p.state.equals("CREATED")) return false;
        p.dispatchStartedAt = clock.instant();
        p.state = "UNKNOWN";
        return true;
    }

    @Transactional
    public boolean closeUnsent(PaymentProvider.Intent i) {
        var o = orders.lock(i.orderId());
        var p = s.lock(PaymentTransaction.class, i.id());
        if (i.channel().equals("WECHAT_PAY")
                && o.cancelRequested
                && p.dispatchStartedAt == null
                && p.state.equals("CREATED")) {
            p.state = "CLOSED";
            orders.close(o);
            return true;
        }
        return false;
    }

    @Transactional
    public boolean cancellationRequested(long orderId) {
        return orders.lock(orderId).cancelRequested;
    }

    @Transactional
    public Long cancel(long id, String key, boolean expiry) {
        var work =
                (java.util.function.Supplier<Object>)
                        () -> {
                            var o = orders.lock(id);
                            if (!expiry) CurrentActor.require().requireOwner(o.userId);
                            if (o.orderStatus.equals("CANCELLED")) return Map.of("paymentId", 0);
                            if (!o.orderStatus.equals("PENDING_PAYMENT")) {
                                if (expiry) return Map.of("paymentId", 0);
                                throw CommerceSupport.conflict(
                                        "INVALID_STATE", "Paid order cannot be cancelled");
                            }
                            if (expiry && o.expiresAt.isAfter(clock.instant()))
                                throw new TaskHandler.RetryLater(
                                        "NOT_EXPIRED",
                                        java.time.Duration.between(clock.instant(), o.expiresAt));
                            o.cancelRequested = true;
                            var rows =
                                    em.createQuery(
                                                    "select p from PaymentTransaction p where"
                                                            + " p.orderId=:o and p.state in"
                                                            + " ('CREATED','PROCESSING','UNKNOWN')",
                                                    PaymentTransaction.class)
                                            .setParameter("o", id)
                                            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                                            .getResultList();
                            if (rows.isEmpty()) {
                                orders.close(o);
                                return Map.of("paymentId", 0);
                            }
                            var p = rows.getFirst();
                            tasks.enqueue(
                                    "PAYMENT_CLOSE", p.getId().toString(), "{}", clock.instant());
                            return Map.of("paymentId", p.getId());
                        };
        var result =
                expiry
                        ? s.node(work.get())
                        : s.execute("CANCEL_ORDER", key, Map.of("orderId", id), work);
        long p = result.path("paymentId").asLong();
        return p == 0 ? null : p;
    }

    @Transactional
    public void applyResult(PaymentProvider.Intent i, PaymentProvider.Result result) {
        var o = orders.lock(i.orderId());
        var p = s.lock(PaymentTransaction.class, i.id());
        if (p.state.equals("SUCCEEDED")) return;
        if (result.state() == PaymentProvider.State.SUCCEEDED)
            throw new IllegalStateException("Success must use verified inbox");
        if (p.state.equals("CLOSED") || p.state.equals("FAILED")) return;
        p.state = result.state().name();
        if (!result.params().isEmpty()) {
            if (p.channel.equals("DEV")) p.paramsJson = s.encode(result.params());
            else p.paramsCipher = cipher.encrypt(s.encode(result.params()));
        }
        if ((p.state.equals("CLOSED") || p.state.equals("FAILED")) && o.cancelRequested)
            orders.close(o);
    }

    @Transactional(readOnly = true)
    public PaymentDtos.View view(long id) {
        var p = em.find(PaymentTransaction.class, id);
        return new PaymentDtos.View(
                p.channel,
                p.getId().toString(),
                p.state,
                p.paramsCipher != null
                        ? s.decode(cipher.decrypt(p.paramsCipher))
                        : p.paramsJson == null ? s.node(Map.of()) : s.decode(p.paramsJson));
    }

    private PaymentProvider.Intent extra(long orderId) {
        var o = orders.lock(orderId);
        CurrentActor.require().requireOwner(o.userId);
        if (!o.channel.equals("DEV") || o.successfulPaymentId == null)
            throw CommerceSupport.conflict(
                    "INVALID_STATE", "DEV extra payment requires a paid DEV order");
        var p = new PaymentTransaction();
        p.orderId = orderId;
        p.outTradeNo = "DEV-EXTRA-" + UUID.randomUUID();
        p.channel = "DEV";
        identity(p);
        p.amountFen = o.payableAmountFen;
        p.state = "CLOSED";
        em.persist(p);
        return intent(p);
    }

    @Transactional
    public PaymentProvider.Intent prepareDev(long orderId, String key, String scenario) {
        var result =
                s.execute(
                        "DEV_PAY",
                        key,
                        Map.of("orderId", orderId, "scenario", scenario),
                        () ->
                                Map.of(
                                        "id",
                                        Long.toString(
                                                (scenario.equals("EXTRA_SUCCESS")
                                                                ? extra(orderId)
                                                                : prepare(orderId, key, true))
                                                        .id())));
        return intent(
                em.find(PaymentTransaction.class, Long.parseLong(result.path("id").asText())));
    }

    private void identity(PaymentTransaction p) {
        p.appId = p.channel.equals("DEV") ? "DEV" : wechat.appId();
        p.merchantId = p.channel.equals("DEV") ? "DEV-MERCHANT" : wechat.merchantId();
        if (p.appId.isBlank() || p.merchantId.isBlank())
            throw new com.wechat.jxsc.common.ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "EXTERNAL_SERVICE_UNAVAILABLE",
                    "Payment merchant identity is not configured");
    }

    @Transactional
    public void registerRefundScenario(long refundId, String key, String scenario) {
        s.execute(
                "DEV_REFUND",
                key,
                Map.of("refundId", refundId, "scenario", scenario),
                () -> {
                    var r = em.find(RefundTransaction.class, refundId);
                    if (r == null) throw CommerceSupport.missing();
                    var o = orders.lock(r.orderId);
                    CurrentActor.require().requireOwner(o.userId);
                    if (!r.channel.equals("DEV"))
                        throw CommerceSupport.conflict("INVALID_CHANNEL", "DEV refunds only");
                    return Map.of("id", Long.toString(refundId));
                });
    }

    @Transactional
    public void exceptionalRefund(StoreOrder o, PaymentTransaction p, String reason) {
        p.refundRequired = true;
        var prior =
                em.createQuery(
                                "select r from RefundTransaction r where r.paymentId=:p and"
                                        + " r.exceptional=true",
                                RefundTransaction.class)
                        .setParameter("p", p.getId())
                        .setLockMode(LockModeType.PESSIMISTIC_READ)
                        .getResultList();
        if (!prior.isEmpty()) return;
        var r = new RefundTransaction();
        r.orderId = o.getId();
        r.paymentId = p.getId();
        r.outRefundNo = "R-" + UUID.randomUUID();
        r.channel = p.channel;
        r.goodsFen = p.amountFen;
        r.freightFen = 0;
        r.state = "CREATED";
        r.reason = reason;
        r.exceptional = true;
        em.persist(r);
        em.flush();
        tasks.enqueue("REFUND_SYNC", r.getId().toString(), "{}", clock.instant());
        s.systemAudit(
                "EXCEPTIONAL_REFUND_CREATED",
                "REFUND",
                r.getId(),
                reason,
                Map.of("amountFen", p.amountFen));
    }
}
