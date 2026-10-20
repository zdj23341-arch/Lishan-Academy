package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CarrierWhitelistIntegrationTest extends CommerceTestSupport {
    @Test
    void publicCarrierCatalogExposesPickerListWithoutLogin() throws Exception {
        var carriers = call("GET", "/logistics/carriers", null, null, 200);
        var codes = new ArrayList<String>();
        carriers.forEach(c -> codes.add(c.path("code").asText()));
        assertThat(codes).contains("shunfeng", "zhongtong", "ems", "DEV");
        assertThat(carriers)
                .anySatisfy(
                        c -> {
                            assertThat(c.path("code").asText()).isEqualTo("shunfeng");
                            assertThat(c.path("name").asText()).isEqualTo("顺丰速运");
                        });
    }

    @Test
    void shipRejectsCarrierOutsideTheCatalog() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(a, b, 1);
        String id = o.path("id").asText();
        var rejection =
                write(
                        "POST",
                        "/admin/orders/" + id + "/ship",
                        a,
                        Map.of(
                                "version",
                                o.path("version").asLong(),
                                "carrierCode",
                                "unknowncarrier",
                                "trackingNo",
                                "X-1",
                                "reason",
                                "Invalid carrier"),
                        UUID.randomUUID().toString(),
                        400);
        assertThat(rejection.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        write(
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "shunfeng",
                        "trackingNo",
                        "SF-1",
                        "reason",
                        "Valid carrier"));
    }

    @Test
    void returnShipmentRejectsCarrierOutsideTheCatalog() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(a, b, 1);
        String id = o.path("id").asText();
        write(
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "shunfeng",
                        "trackingNo",
                        "SF-RT",
                        "reason",
                        "Ship for return"));
        String sale =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "RETURN_REFUND",
                                        "reason",
                                        "Carrier whitelist",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        2000,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + sale + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        2000,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        1,
                        "reason",
                        "Approve return",
                        "returnAddress",
                        "DEV warehouse"));
        var rejection =
                write(
                        "POST",
                        "/aftersales/" + sale + "/return-shipment",
                        b,
                        Map.of("carrierCode", "unknowncarrier", "trackingNo", "X"),
                        UUID.randomUUID().toString(),
                        400);
        assertThat(rejection.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        write(
                "/aftersales/" + sale + "/return-shipment",
                b,
                Map.of("carrierCode", "yunda", "trackingNo", "YD-1"));
    }
}
