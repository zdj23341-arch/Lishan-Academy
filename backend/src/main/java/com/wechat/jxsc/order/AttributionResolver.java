package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;

/** Task 6 resolves authenticated tokens and locks attribution inside the order transaction. */
public interface AttributionResolver {
    JsonNode resolveAndLock(long userId, long productId, String token, long orderId, long goodsFen);
}
