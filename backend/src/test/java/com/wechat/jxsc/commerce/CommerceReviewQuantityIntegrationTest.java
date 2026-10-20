package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.*;

class CommerceReviewQuantityIntegrationTest extends CommerceTestSupport {
    private void restore(String a, String b, String id, int quantity) throws Exception {
        String c =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Explicit unshipped quantity",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        1000,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + c + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        1000,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        quantity,
                        "reason",
                        "Restore explicit quantity"));
        write("/dev/aftersales/" + c + "/refund", b, Map.of("scenario", "SUCCESS"));
    }

    private JsonNode ship(String a, String b, String id, int status) throws Exception {
        var o = call("GET", "/orders/" + id, b, null, 200);
        return write(
                "POST",
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-QUANTITY",
                        "reason",
                        "Ship remaining copies"),
                UUID.randomUUID().toString(),
                status);
    }

    @Test
    void allRestoredCopiesCannotShipAfterAnotherBuyerPurchasesTheStock() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer"), other = buyer("buyer-active-member");
        String p = book(a, 1, 2000);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        restore(a, b, id, 1);
        String second =
                order(other, quote(other, p, address(other).path("id").asText(), 1))
                        .path("id")
                        .asText();
        write("/dev/orders/" + second + "/pay", other, Map.of("scenario", "SUCCESS"));
        ship(a, b, id, 409);
        var o = call("GET", "/orders/" + id, b, null, 200);
        assertThat(o.path("item").path("quantity").asInt()).isEqualTo(1);
        assertThat(o.path("item").path("fulfillableQuantity").asInt(-1)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from shipment where order_id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void partialRestoreShipsOnlyRemainingCopiesAndTrackingAndReturnPreserveShipmentQuantity()
            throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var initial = paidOrder(a, b, 3);
        String id = initial.path("id").asText();
        restore(a, b, id, 1);
        var shipped = ship(a, b, id, 200);
        assertThat(shipped.path("item").path("quantity").asInt()).isEqualTo(3);
        assertThat(shipped.path("item").path("shippedQuantity").asInt(-1)).isEqualTo(2);
        assertThat(
                        call("GET", "/orders/" + id + "/logistics", b, null, 200)
                                .path("shippedQuantity")
                                .asInt(-1))
                .isEqualTo(2);
        ship(a, b, id, 200);
        String c =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "RETURN_REFUND",
                                        "reason",
                                        "Return one shipped copy",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        100,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + c + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        100,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        1,
                        "returnAddress",
                        "DEV warehouse",
                        "reason",
                        "Inspect copy"));
        write(
                "/aftersales/" + c + "/return-shipment",
                b,
                Map.of("carrierCode", "DEV", "trackingNo", "DEV-RETURN-QUANTITY"));
        write(
                "/admin/aftersales/" + c + "/receive-return",
                a,
                Map.of("returnedQuantity", 1, "reason", "Received one copy"));
        write("/dev/aftersales/" + c + "/refund", b, Map.of("scenario", "FAILURE"));
        ship(a, b, id, 200);
        assertThat(
                        call("GET", "/orders/" + id + "/logistics", b, null, 200)
                                .path("shippedQuantity")
                                .asInt(-1))
                .isEqualTo(2);
    }
}
