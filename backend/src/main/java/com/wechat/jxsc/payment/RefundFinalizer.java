package com.wechat.jxsc.payment;

import com.wechat.jxsc.catalog.InventoryService;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

@Service
public class RefundFinalizer {
    @PersistenceContext private EntityManager em;
    private final OrderService orders;
    private final InventoryService inventory;
    private final CommerceSupport s;
    private final TaskQueue tasks;
    private final Clock clock;
    private final FinancialReconciliationService reconciliation;

    public RefundFinalizer(
            OrderService orders,
            InventoryService inventory,
            CommerceSupport s,
            TaskQueue tasks,
            Clock clock,
            FinancialReconciliationService reconciliation) {
        this.orders = orders;
        this.inventory = inventory;
        this.s = s;
        this.tasks = tasks;
        this.clock = clock;
        this.reconciliation = reconciliation;
    }

    public String apply(StoreOrder o, PaymentProvider.VerifiedEvent e, long inboxId) {
        var r = s.lock(RefundTransaction.class, e.transactionId());
        var payment = em.find(PaymentTransaction.class, r.paymentId);
        if (!r.orderId.equals(o.getId())
                || !r.channel.equals(e.provider())
                || !r.outRefundNo.equals(e.businessNo())
                || r.goodsFen + r.freightFen != e.amountFen()
                || !e.currency().equals("CNY")
                || !Objects.equals(e.appId(), payment.appId)
                || !Objects.equals(e.merchantId(), payment.merchantId)
                || e.channelId() == null
                || e.channelId().isBlank()) return "REFUND_MISMATCH";
        if (r.state.equals("SUCCEEDED"))
            return Objects.equals(r.channelRefundId, e.channelId()) ? null : "REFUND_MISMATCH";
        var corrections =
                em.createNativeQuery(
                                "select channel_id from economic_resolution where"
                                        + " resource_type='REFUND' and transaction_id=:id")
                        .setParameter("id", r.getId())
                        .getResultList();
        if (!corrections.isEmpty())
            return Objects.equals(corrections.getFirst(), e.channelId()) ? null : "REFUND_MISMATCH";
        if (e.state() != PaymentProvider.State.SUCCEEDED) return null;
        if (Set.of("FAILED", "CLOSED").contains(r.state)
                || o.financialHold
                || !r.exceptional
                        && (r.goodsFen > o.goodsAmountFen - o.refundedGoodsFen
                                || r.freightFen > o.freightAmountFen - o.refundedFreightFen)) {
            reconciliation.observeRefund(o, r, e, inboxId);
            return null;
        }
        r.state = "SUCCEEDED";
        r.channelRefundId = e.channelId();
        r.successAt = clock.instant();
        if (!r.exceptional) {
            var a = s.lock(AftersaleCase.class, r.aftersaleId);
            var i = orders.item(o.getId());
            if (r.goodsFen > o.goodsAmountFen - o.refundedGoodsFen
                    || r.freightFen > o.freightAmountFen - o.refundedFreightFen)
                throw new IllegalStateException("Refund budget exceeded");
            o.refundedGoodsFen += r.goodsFen;
            o.refundedFreightFen += r.freightFen;
            i.goodsRefundedFen += r.goodsFen;
            if (r.restockQuantity > 0) {
                boolean unshipped = o.fulfillmentStatus.equals("PENDING_SHIPMENT");
                boolean returned =
                        a.type.equals("RETURN_REFUND")
                                && a.receivedAt != null
                                && a.receivedQuantity >= r.restockQuantity;
                if (!i.type.equals("BOOK")
                        || !unshipped && !returned
                        || i.restoredQuantity + r.restockQuantity > i.quantity)
                    throw new IllegalStateException("Invalid restock confirmation");
                inventory.change(
                        i.productId,
                        i.getId(),
                        "RETURN",
                        r.restockQuantity,
                        0,
                        "refund:" + r.getId(),
                        "Refund succeeded with explicit received quantity",
                        true);
                i.restoredQuantity += r.restockQuantity;
            }
            o.refundStatus =
                    o.refundedGoodsFen == o.goodsAmountFen
                                    && o.refundedFreightFen == o.freightAmountFen
                            ? "FULL"
                            : "PARTIAL";
            a.status = o.refundStatus;
            if (Objects.equals(o.activeAftersaleId, a.getId())) o.activeAftersaleId = null;
            tasks.enqueue(
                    "REFUND_SUCCEEDED",
                    r.getId().toString(),
                    s.encode(
                            Map.of(
                                    "orderId",
                                    o.getId().toString(),
                                    "refundId",
                                    r.getId().toString(),
                                    "fullGoodsRefund",
                                    o.refundedGoodsFen == o.goodsAmountFen)),
                    clock.instant());
            tasks.enqueue(
                    "AFTERSALE_CLOSED",
                    a.getId().toString(),
                    s.encode(Map.of("orderId", o.getId().toString())),
                    clock.instant());
        }
        s.systemAudit(
                "REFUND_SUCCEEDED",
                "REFUND",
                r.getId(),
                "Verified provider refund",
                Map.of(
                        "goodsFen",
                        r.goodsFen,
                        "freightFen",
                        r.freightFen,
                        "exceptional",
                        r.exceptional));
        return null;
    }
}
