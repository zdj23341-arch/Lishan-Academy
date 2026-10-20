package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.tasks.TaskWorker;
import com.wechat.jxsc.order.CommerceEventDispatcher;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Import(EntitlementTestSupport.TimeConfiguration.class)
abstract class EntitlementTestSupport extends CommerceTestSupport {
    @Autowired CommerceEventDispatcher events;
    @Autowired TaskWorker worker;
    @Autowired TestClock clock;
    String a, b;
    long uid;

    public static class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-10T02:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        TestClock entitlementClock() {
            return new TestClock();
        }
    }

    @BeforeEach
    void setup() throws Exception {
        clock.now = Instant.parse("2026-10-10T02:00:00Z");
        a = admin("SUPER_ADMIN");
        buyer("buyer");
        String nick = "DEV entitlement " + UUID.randomUUID();
        jdbc.update(
                "insert into user_account(nickname,status,created_at,updated_at,version) values"
                        + " (?,'ACTIVE',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                nick);
        uid = jdbc.queryForObject("select id from user_account where nickname=?", Long.class, nick);
        jdbc.update(
                "update user_wechat_identity set user_id=? where app_id='DEV' and openid='buyer'",
                uid);
        b = buyer("buyer");
    }

    JsonNode course(String validity, Integer days) throws Exception {
        var p = createProduct(a, "COURSE", "DEV course " + UUID.randomUUID(), 100);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 0);
        body.put("productId", p.path("id").asText());
        body.put("validityType", validity);
        body.put("validityDays", days);
        var c = call("POST", "/admin/courses", a, body, 200);
        publish(a, p);
        return c;
    }

    String member(int months) throws Exception {
        jdbc.update("update membership_plan set status='DISABLED' where duration_months=?", months);
        var body = product("MEMBERSHIP", "DEV member " + UUID.randomUUID(), 100);
        body.put("membership", Map.of("durationMonths", months));
        return publish(a, call("POST", "/admin/products", a, body, 200)).path("id").asText();
    }

    String paid(String product, String at) throws Exception {
        var q = write("/orders/preview", b, Map.of("productId", product, "quantity", 1));
        String id = order(b, q).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        jdbc.update(
                "update store_order set paid_at=? where id=?",
                Timestamp.from(Instant.parse(at)),
                id);
        return id;
    }

    void deliver(String id) {
        assertThatCode(() -> events.handle("ENTITLEMENT_DELIVER", id, "{}"))
                .doesNotThrowAnyException();
    }

    JsonNode me() throws Exception {
        return call("GET", "/membership/me", b, null, 200);
    }

    void refund(String id, boolean full, String key) {
        if (full)
            jdbc.update(
                    "update store_order set refunded_goods_fen=goods_amount_fen where id=?", id);
        try {
            events.handle(
                    "REFUND_SUCCEEDED",
                    key,
                    "{\"orderId\":\"" + id + "\",\"fullGoodsRefund\":" + full + "}");
        } catch (com.wechat.jxsc.common.tasks.TaskHandler.RetryLater pending) {
            assertThat(pending.getMessage()).contains("CONSUMER_PENDING");
        }
    }
}
