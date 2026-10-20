package com.wechat.jxsc.membership;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.order.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class EntitlementConsumer implements CommerceEventConsumer {
    private final OrderService orders;
    private final EntitlementLedger ledger;
    private final CommerceSupport support;

    public EntitlementConsumer(
            OrderService orders, EntitlementLedger ledger, CommerceSupport support) {
        this.orders = orders;
        this.ledger = ledger;
        this.support = support;
    }

    public String name() {
        return "ENTITLEMENTS";
    }

    public boolean supports(String type) {
        return type.equals("ENTITLEMENT_DELIVER") || type.equals("REFUND_SUCCEEDED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(String type, String key, JsonNode payload) {
        long id =
                Long.parseLong(
                        type.equals("ENTITLEMENT_DELIVER")
                                ? key
                                : payload.path("orderId").asText());
        var o = orders.lock(id);
        orders.requireFinancialClear(o);
        var item = orders.item(id);
        if (item.type.equals("BOOK")) return;
        String source = "ORDER_ITEM:" + item.getId();
        boolean member = item.type.equals("MEMBERSHIP");
        if (type.equals("REFUND_SUCCEEDED")) {
            // Current accounted state, not an old/out-of-order event flag, decides full revocation.
            if (o.refundedGoodsFen == o.goodsAmountFen && o.payableAmountFen > 0)
                ledger.revoke(o.userId, item.getId(), source, member);
            return;
        }
        if (o.paidAt == null)
            throw new IllegalStateException("Entitlement delivery requires confirmed payment");
        if (o.refundedGoodsFen == o.goodsAmountFen && o.payableAmountFen > 0) return;
        JsonNode terms = support.decode(item.fulfillmentSnapshotJson);
        ledger.grant(
                o.userId,
                item.getId(),
                source,
                member ? null : Long.parseLong(terms.path("courseId").asText()),
                o.paidAt,
                member || terms.path("validityType").asText().equals("PERMANENT")
                        ? null
                        : terms.path("validityDays").asInt(),
                member ? terms.path("durationMonths").asInt() : null);
    }
}
