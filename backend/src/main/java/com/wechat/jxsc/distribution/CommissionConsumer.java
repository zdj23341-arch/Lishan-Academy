package com.wechat.jxsc.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.order.CommerceEventConsumer;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CommissionConsumer implements CommerceEventConsumer {
    private final CommissionService commissions;

    public CommissionConsumer(CommissionService commissions) {
        this.commissions = commissions;
    }

    public String name() {
        return "COMMISSIONS";
    }

    public boolean supports(String type) {
        return Set.of(
                        "ORDER_PAID",
                        "ORDER_COMPLETED",
                        "AFTERSALE_OPENED",
                        "AFTERSALE_CLOSED",
                        "REFUND_SUCCEEDED")
                .contains(type);
    }

    public void consume(String type, String key, JsonNode payload) {
        long order =
                Long.parseLong(payload.has("orderId") ? payload.path("orderId").asText() : key);
        Long refund =
                payload.hasNonNull("refundId")
                        ? Long.valueOf(payload.path("refundId").asText())
                        : null;
        commissions.reconcile(order, refund);
    }
}
