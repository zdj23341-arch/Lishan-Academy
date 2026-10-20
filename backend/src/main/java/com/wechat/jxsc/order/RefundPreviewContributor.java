package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;

/** Tasks 5/6 provide actual entitlement/commission effects under the same order lock. */
public interface RefundPreviewContributor {
    String name();

    JsonNode preview(long orderId, long goodsFen, long freightFen, boolean fullRefund);
}
