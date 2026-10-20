package com.wechat.jxsc.fulfillment;

import java.util.List;

public interface LogisticsProvider {
    record Tracking(String status, boolean simulated, List<FulfillmentDtos.Event> events) {}

    Tracking query(String carrierCode, String trackingNo);

    default Tracking query(String carrierCode, String trackingNo, String phone) {
        return query(carrierCode, trackingNo);
    }
}
