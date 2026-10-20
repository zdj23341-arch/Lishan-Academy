package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.tasks.TaskWorker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

class CommerceEventsIntegrationTest extends CommerceTestSupport {
    @Autowired TaskWorker worker;

    @Test
    void installedConsumerCommitsOnceAndOnlyThenCompletesDigitalOrder() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var p = createProduct(a, "COURSE", "DEV consumer " + UUID.randomUUID(), 0);
        String pid = p.path("id").asText();
        call(
                "POST",
                "/admin/courses",
                a,
                Map.of("version", 0, "productId", pid, "validityType", "DAYS", "validityDays", 30),
                200);
        publish(a, p);
        var o = order(b, write("/orders/preview", b, Map.of("productId", pid, "quantity", 1)));
        String id = o.path("id").asText();
        worker.run(30);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("COMPLETED");
        jdbc.update(
                "update outbox_task set"
                        + " status='READY',next_run_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)"
                        + " where task_type='ENTITLEMENT_DELIVER' and business_key=?",
                id);
        worker.run(30);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement where order_item_id=(select id"
                                        + " from order_item where order_id=?)",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commerce_event_receipt where"
                                        + " event_type='ENTITLEMENT_DELIVER' and business_key=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }
}
