package com.wechat.jxsc.order;

import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.common.tasks.TaskHandler;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** One outbox handler fans out to independently deduplicated future-stage consumers. */
@Component
public class CommerceEventDispatcher implements TaskHandler {
    private final List<CommerceEventConsumer> consumers;
    private final CommerceEventDelivery delivery;
    private final CommerceSupport s;

    public CommerceEventDispatcher(
            List<CommerceEventConsumer> consumers,
            CommerceEventDelivery delivery,
            CommerceSupport s) {
        this.consumers = consumers;
        this.delivery = delivery;
        this.s = s;
    }

    public boolean supports(String type) {
        return Set.of(
                        "ORDER_PAID",
                        "REFUND_SUCCEEDED",
                        "ORDER_COMPLETED",
                        "ENTITLEMENT_DELIVER",
                        "AFTERSALE_OPENED",
                        "AFTERSALE_CLOSED")
                .contains(type);
    }

    public void handle(String type, String key, String payload) {
        var data = s.decode(payload);
        boolean missing = false;
        for (String name : delivery.required(type, key, data)) {
            var matches =
                    consumers.stream()
                            .filter(c -> c.name().equals(name) && c.supports(type))
                            .toList();
            if (matches.size() != 1) {
                missing = true;
                continue;
            }
            delivery.deliver(matches.getFirst(), type, key, data);
        }
        if (missing) throw new RetryLater("CONSUMER_PENDING", Duration.ofMinutes(5));
    }
}
