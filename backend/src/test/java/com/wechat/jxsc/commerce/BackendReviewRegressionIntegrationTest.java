package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class BackendReviewRegressionIntegrationTest extends EntitlementTestSupport {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "false,false",
        "false,true",
        "true,false",
        "true,true"
    })
    void zeroChargeContentActionTracksEffectiveEntitlement(boolean membership, boolean revoked)
            throws Exception {
        if (membership)
            jdbc.update("update membership_plan set status='DISABLED' where duration_months=6");
        var p =
                createProduct(
                        a,
                        membership ? "MEMBERSHIP" : "COURSE",
                        "DEV free " + UUID.randomUUID(),
                        0);
        String pid = p.path("id").asText();
        if (!membership)
            call(
                    "POST",
                    "/admin/courses",
                    a,
                    Map.of(
                            "version",
                            0,
                            "productId",
                            pid,
                            "validityType",
                            "DAYS",
                            "validityDays",
                            30),
                    200);
        publish(a, p);
        String id =
                order(b, write("/orders/preview", b, Map.of("productId", pid, "quantity", 1)))
                        .path("id")
                        .asText();
        assertThat(actions(id)).doesNotContain("VIEW_CONTENT");
        deliver(id);
        assertThat(actions(id)).contains("VIEW_CONTENT");
        if (revoked) {
            jdbc.update(
                    "insert into"
                        + " entitlement_event(user_id,source_key,event_key,event_type,order_item_id,detail_json)"
                        + " select"
                        + " user_id,source_key,concat('review-revoke-',id),'REVOKE',order_item_id,'{}'"
                        + " from entitlement where order_item_id=(select id from order_item where"
                        + " order_id=?)",
                    id);
        } else {
            clock.now = clock.now.plusSeconds(400 * 86400L);
            b = buyer("buyer");
        }
        assertThat(actions(id)).doesNotContain("VIEW_CONTENT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullyRefundedDigitalOrderHidesActionBeforeAndAfterRevocation(boolean membership)
            throws Exception {
        String pid = membership ? member(6) : course("DAYS", 30).path("productId").asText();
        String id = paid(pid, clock.instant().toString());
        deliver(id);
        assertThat(actions(id)).contains("VIEW_CONTENT");
        jdbc.update("update store_order set refunded_goods_fen=goods_amount_fen where id=?", id);
        assertThat(actions(id)).doesNotContain("VIEW_CONTENT");
        refund(id, true, "review-revoke-" + id);
        assertThat(actions(id)).doesNotContain("VIEW_CONTENT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outstandingTabsExcludeFullRefundAndRetainPartialRefund(boolean shipped) throws Exception {
        String full = refundBook(shipped, false);
        String partial = refundBook(shipped, true);
        String tab = shipped ? "SHIPPING" : "UNSHIPPED";
        var page = call("GET", "/orders?tab=" + tab + "&pageSize=1", b, null, 200);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(ids(page)).containsExactly(partial);
        assertThat(ids(call("GET", "/orders?tab=ALL", b, null, 200))).contains(full, partial);
        assertThat(ids(call("GET", "/orders?tab=AFTERSALE", b, null, 200))).contains(full, partial);
        var detail = call("GET", "/orders/" + full, b, null, 200);
        assertThat(detail.path("refundStatus").asText()).isEqualTo("FULL");
        assertThat(detail.path("fulfillmentStatus").asText())
                .isEqualTo(shipped ? "SHIPPED" : "PENDING_SHIPMENT");
    }

    private String refundBook(boolean shipped, boolean partial) throws Exception {
        var o = paidOrder(a, b, partial ? 2 : 1);
        String id = o.path("id").asText();
        if (shipped)
            write(
                    "/admin/orders/" + id + "/ship",
                    a,
                    Map.of(
                            "version",
                            o.path("version").asLong(),
                            "carrierCode",
                            "DEV",
                            "trackingNo",
                            "DEV-REVIEW-" + id,
                            "reason",
                            "Regression shipment"));
        String sale =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        shipped ? "RETURN_REFUND" : "REFUND_ONLY",
                                        "reason",
                                        "Regression refund",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        2000,
                                        "requestedFreightFen",
                                        partial ? 0 : 600))
                        .path("id")
                        .asText();
        var decision =
                new HashMap<String, Object>(
                        Map.of(
                                "goodsRefundFen",
                                2000,
                                "freightRefundFen",
                                partial ? 0 : 600,
                                "returnedQuantity",
                                1,
                                "reason",
                                "Regression approval"));
        if (shipped) decision.put("returnAddress", "DEV warehouse");
        write("/admin/aftersales/" + sale + "/approve", a, decision);
        if (shipped) {
            write(
                    "/aftersales/" + sale + "/return-shipment",
                    b,
                    Map.of("carrierCode", "DEV", "trackingNo", "DEV-RETURN-" + id));
            write(
                    "/admin/aftersales/" + sale + "/receive-return",
                    a,
                    Map.of("returnedQuantity", 1, "reason", "Regression received"));
        }
        write("/dev/aftersales/" + sale + "/refund", b, Map.of("scenario", "SUCCESS"));
        return id;
    }

    private List<String> actions(String id) throws Exception {
        var result = new ArrayList<String>();
        call("GET", "/orders/" + id, b, null, 200)
                .path("allowedActions")
                .forEach(v -> result.add(v.asText()));
        return result;
    }

    private List<String> ids(JsonNode page) {
        var result = new ArrayList<String>();
        page.path("items").forEach(v -> result.add(v.path("id").asText()));
        return result;
    }
}
