package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

class EntitlementIntegrationTest extends EntitlementTestSupport {
    @Test
    void courseDaysAndPermanentAreSeparateFromMembershipAndDuplicateCheckout() throws Exception {
        var c = course("DAYS", 30);
        String id = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        deliver(id);
        deliver(id);
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("ACTIVE");
        assertThat(me().path("status").asText()).isEqualTo("NONE");
        var q =
                write(
                        "/orders/preview",
                        b,
                        Map.of("productId", c.path("productId").asText(), "quantity", 1));
        assertThat(rawCreate(b, q).path("code").asText()).isEqualTo("ALREADY_PURCHASED");
        assertThat(
                        jdbc.queryForObject(
                                        "select expires_at from entitlement where"
                                                + " order_item_id=(select id from order_item where"
                                                + " order_id=?)",
                                        Timestamp.class,
                                        id)
                                .toInstant())
                .isEqualTo(Instant.parse("2026-11-05T02:00:00Z"));
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("COMPLETED");
        clock.now = Instant.parse("2026-11-05T02:00:00Z");
        b = buyer("buyer");
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("EXPIRED");
        a = admin("SUPER_ADMIN");
        var forever = course("PERMANENT", null);
        String foreverOrder = paid(forever.path("productId").asText(), "2026-10-06T02:00:00Z");
        deliver(foreverOrder);
        assertThat(
                        jdbc.queryForObject(
                                "select expires_at from entitlement where order_item_id=(select id"
                                        + " from order_item where order_id=?)",
                                Timestamp.class,
                                foreverOrder))
                .isNull();
    }

    @Test
    void calendarRenewalReplayPreservesOriginalTermsAndFullRefundRevokesOnlySource()
            throws Exception {
        String half = member(6), year = member(12);
        String first = paid(half, "2026-10-06T02:00:00Z"),
                second = paid(year, "2026-10-10T02:00:00Z");
        deliver(first);
        deliver(second);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2028-04-06T02:00:00Z");
        var original =
                jdbc.queryForList(
                        "select original_start_at,original_end_at from membership_grant where"
                                + " user_id=? order by paid_at,id",
                        uid);
        refund(first, false, "partial-" + first);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2028-04-06T02:00:00Z");
        refund(first, true, "full-" + first);
        refund(first, true, "full-" + first);
        deliver(first);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2027-10-10T02:00:00Z");
        assertThat(
                        jdbc.queryForList(
                                "select original_start_at,original_end_at from membership_grant"
                                        + " where user_id=? order by paid_at,id",
                                uid))
                .isEqualTo(original);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement_event where user_id=? and"
                                        + " event_type='REVOKE'",
                                Integer.class,
                                uid))
                .isEqualTo(1);
        var c = course("DAYS", 30);
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("NONE");
    }

    @Test
    void monthEndAndHalfOpenExpiryAreLiteral() throws Exception {
        String id = paid(member(6), "2026-08-31T02:00:00Z");
        deliver(id);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2027-02-28T02:00:00Z");
        clock.now = Instant.parse("2027-02-28T01:59:59.999999Z");
        b = buyer("buyer");
        assertThat(me().path("status").asText()).isEqualTo("ACTIVE");
        clock.now = Instant.parse("2027-02-28T02:00:00Z");
        assertThat(me().path("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void refundBeforeDeliveryAndFinancialHoldCannotCreateRightsOrReceipts() throws Exception {
        var c = course("DAYS", 30);
        String held = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        jdbc.update("update store_order set financial_hold=true where id=?", held);
        assertThatThrownBy(() -> events.handle("ENTITLEMENT_DELIVER", held, "{}"))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commerce_event_receipt where business_key=?"
                                        + " and consumer_name='ENTITLEMENTS'",
                                Integer.class,
                                held))
                .isZero();
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("NONE");
        jdbc.update("update store_order set financial_hold=false where id=?", held);
        refund(held, true, "before-" + held);
        deliver(held);
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("NONE");
    }
}
