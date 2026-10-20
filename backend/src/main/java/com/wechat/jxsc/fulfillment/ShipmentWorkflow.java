package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.common.tasks.*;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
public class ShipmentWorkflow implements TaskHandler {
    private final ShipmentService shipments;
    private final LogisticsProvider provider;
    private final CommerceSupport s;

    public ShipmentWorkflow(
            ShipmentService shipments, LogisticsProvider provider, CommerceSupport s) {
        this.shipments = shipments;
        this.provider = provider;
        this.s = s;
    }

    public void sync(long id) {
        var intent = shipments.prepare(id);
        if (intent != null) {
            try {
                shipments.synced(
                        intent,
                        provider.query(intent.carrier(), intent.tracking(), intent.phone()));
            } catch (RuntimeException e) {
                shipments.syncFailed(intent);
                throw e;
            }
        }
    }

    public boolean supports(String type) {
        return Set.of("LOGISTICS_SYNC", "AUTO_RECEIVE").contains(type);
    }

    public void handle(String type, String key, String payload) {
        if (type.equals("AUTO_RECEIVE")) {
            try {
                shipments.receive(Long.parseLong(key), null, true);
            } catch (com.wechat.jxsc.common.ApiException e) {
                if (e.getCode().equals("ACTIVE_AFTERSALE"))
                    throw new RetryLater("ACTIVE_AFTERSALE", Duration.ofHours(1));
                throw e;
            }
        } else {
            long id = s.decode(payload).path("orderId").asLong();
            if (shipments.finished(id)) return;
            sync(id);
            throw new RetryLater("LOGISTICS_SCHEDULED", shipments.cadence());
        }
    }
}
