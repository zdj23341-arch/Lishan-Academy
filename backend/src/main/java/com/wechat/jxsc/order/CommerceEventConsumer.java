package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Task 5 uses ENTITLEMENTS, Task 6 COMMISSIONS. Consumers commit local effects in the caller
 * transaction.
 */
public interface CommerceEventConsumer {
    String name();

    boolean supports(String type);

    void consume(String type, String businessKey, JsonNode payload);
}
