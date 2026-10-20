package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CommerceContractIntegrationTest extends CommerceTestSupport {
    @Test
    void shipRequiresCurrentOrderVersionAndRejectsStaleTrackingEdits() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(a, b, 1);
        String id = o.path("id").asText();
        write(
                "POST",
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-MISSING",
                        "reason",
                        "Missing version"),
                UUID.randomUUID().toString(),
                400);
        var request =
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-FIRST",
                        "reason",
                        "Ship DEV book");
        write("/admin/orders/" + id + "/ship", a, request);
        assertThat(
                        write(
                                        "POST",
                                        "/admin/orders/" + id + "/ship",
                                        a,
                                        request,
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void financialDecisionPermissionIsSeparateFromWarehouseOperations() throws Exception {
        String a = admin("SUPER_ADMIN"),
                finance = admin("FINANCE_OPERATOR"),
                ops = admin("ORDER_OPERATOR"),
                b = buyer("buyer");
        String id = paidOrder(a, b, 1).path("id").asText();
        String cid =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "DEV financial review",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        100,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        call("GET", "/admin/aftersales", finance, null, 200);
        call("GET", "/admin/aftersales", ops, null, 403);
        var decision =
                Map.of(
                        "goodsRefundFen",
                        100,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "Authorized refund");
        write(
                "POST",
                "/admin/aftersales/" + cid + "/approve",
                ops,
                decision,
                UUID.randomUUID().toString(),
                403);
        write("/admin/aftersales/" + cid + "/approve", finance, decision);
    }

    @Test
    void quotesPersistOnlyTokenHashesAndAreBoundToTheirOwner() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer"), other = buyer("buyer-active-member");
        String p = book(a, 2, 2000);
        var q = quote(b, p, address(b).path("id").asText(), 1);
        String token = q.path("quoteId").asText();
        assertThat(token.length()).isGreaterThanOrEqualTo(40);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from order_quote where token_hash=?",
                                Integer.class,
                                com.wechat.jxsc.auth.TokenCodec.hash(token)))
                .isEqualTo(1);
        write(
                "POST",
                "/orders",
                other,
                Map.of("quoteId", token),
                UUID.randomUUID().toString(),
                404);
    }

    @Test
    void adminOrderFiltersUseNumberDatesAndThreeIndependentStates() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(a, b, 1);
        String number = o.path("orderNo").asText();
        var rows =
                call(
                        "GET",
                        "/admin/orders?orderNo="
                                + number
                                + "&orderStatus=PAID&refundStatus=NONE&fulfillmentStatus=PENDING_SHIPMENT&from=2020-01-01T00:00:00Z&to=2099-01-01T00:00:00Z",
                        a,
                        null,
                        200);
        assertThat(rows.path("total").asLong()).isEqualTo(1);
        assertThat(rows.path("items").get(0).path("id")).isEqualTo(o.path("id"));
    }
}
