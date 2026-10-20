package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CommerceEventDelivery {
    @PersistenceContext private EntityManager em;
    private final OrderService orders;
    private final CommerceSupport s;

    public CommerceEventDelivery(OrderService orders, CommerceSupport s) {
        this.orders = orders;
        this.s = s;
    }

    private long orderId(String type, String key, JsonNode payload) {
        return Set.of("ORDER_PAID", "ORDER_COMPLETED", "ENTITLEMENT_DELIVER").contains(type)
                ? Long.parseLong(key)
                : Long.parseLong(payload.path("orderId").asText());
    }

    @Transactional(readOnly = true)
    public Set<String> required(String type, String key, JsonNode payload) {
        if (type.equals("ENTITLEMENT_DELIVER")) return Set.of("ENTITLEMENTS");
        if (type.equals("REFUND_SUCCEEDED")
                && !orders.item(orderId(type, key, payload)).type.equals("BOOK"))
            return Set.of("ENTITLEMENTS", "COMMISSIONS");
        return Set.of("COMMISSIONS");
    }

    /**
     * Consumer effects and permanent receipt commit together; a failed consumer rolls both back.
     */
    @Transactional
    public void deliver(CommerceEventConsumer consumer, String type, String key, JsonNode payload) {
        var o = orders.lock(orderId(type, key, payload));
        orders.requireFinancialClear(o);
        String name = consumer.name();
        var rows =
                em.createNativeQuery(
                                "select id from commerce_event_receipt where event_type=:t and"
                                        + " business_key=:k and consumer_name=:c for update")
                        .setParameter("t", type)
                        .setParameter("k", key)
                        .setParameter("c", name)
                        .getResultList();
        if (!rows.isEmpty()) return;
        if (type.equals("ENTITLEMENT_DELIVER")) {
            if (o.paidAt == null) throw new IllegalStateException("Order is not paid");
            if (o.refundedGoodsFen == o.goodsAmountFen && o.payableAmountFen > 0) {
                record(type, key, name);
                return;
            }
        }
        consumer.consume(type, key, payload);
        if (type.equals("ENTITLEMENT_DELIVER")) orders.complete(o);
        record(type, key, name);
    }

    private void record(String type, String key, String consumer) {
        em.createNativeQuery(
                        "insert into"
                            + " commerce_event_receipt(event_type,business_key,consumer_name,created_at)"
                            + " values (:t,:k,:c,UTC_TIMESTAMP(6))")
                .setParameter("t", type)
                .setParameter("k", key)
                .setParameter("c", consumer)
                .executeUpdate();
    }
}
