package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CommerceBoundaryIntegrationTest extends CommerceTestSupport {
    @Test
    void allCustomerResourcesAndOperatorRoutesEnforceOwnershipAndPermission() throws Exception {
        String a = admin("SUPER_ADMIN"),
                editor = admin("CONTENT_EDITOR"),
                b = buyer("buyer"),
                other = buyer("buyer-active-member");
        var o = paidOrder(a, b, 1);
        String id = o.path("id").asText();
        call("GET", "/orders/" + id, other, null, 404);
        write(
                "POST",
                "/orders/" + id + "/cancel",
                other,
                Map.of(),
                UUID.randomUUID().toString(),
                404);
        call("GET", "/admin/orders", b, null, 403);
        call("GET", "/admin/orders", editor, null, 403);
        var c =
                write(
                        "/orders/" + id + "/refund",
                        b,
                        Map.of(
                                "type",
                                "REFUND_ONLY",
                                "reason",
                                "DEV owned case",
                                "evidenceAssetIds",
                                List.of(),
                                "requestedGoodsFen",
                                100,
                                "requestedFreightFen",
                                0));
        String cid = c.path("id").asText();
        call("GET", "/aftersales/" + cid, other, null, 404);
        write(
                "POST",
                "/dev/aftersales/" + cid + "/refund",
                other,
                Map.of("scenario", "SUCCESS"),
                UUID.randomUUID().toString(),
                404);
    }

    @Test
    void quoteBindsCourseTermsAndShippingRuleVersionsAndHonorsFreightThreshold() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 10, 2000);
        String addr = address(b).path("id").asText();
        var q = quote(b, p, addr, 5);
        assertThat(q.path("freightAmountFen").asLong()).isZero();
        jdbc.update("update shipping_rule set version=version+1 where province_code='310000'");
        write(
                "POST",
                "/orders",
                b,
                Map.of("quoteId", q.path("quoteId").asText()),
                UUID.randomUUID().toString(),
                409);
        var cp = createProduct(a, "COURSE", "DEV version " + UUID.randomUUID(), 2000);
        String pid = cp.path("id").asText();
        call(
                "POST",
                "/admin/courses",
                a,
                Map.of("version", 0, "productId", pid, "validityType", "DAYS", "validityDays", 30),
                200);
        publish(a, cp);
        var cq = write("/orders/preview", b, Map.of("productId", pid, "quantity", 1));
        jdbc.update("update course set version=version+1,validity_days=60 where product_id=?", pid);
        assertThat(
                        write(
                                        "POST",
                                        "/orders",
                                        b,
                                        Map.of("quoteId", cq.path("quoteId").asText()),
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("ORDER_STALE");
        write(
                "POST",
                "/orders/preview",
                b,
                Map.of("productId", pid, "quantity", 2),
                UUID.randomUUID().toString(),
                400);
    }

    @Test
    void unserviceableRegionAndMissingOrFractionalMoneyAreRejected() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 2, 2000);
        var body = new LinkedHashMap<>(addressBody(false));
        body.put("provinceCode", "990000");
        var ad = call("POST", "/addresses", b, body, 200);
        assertThat(
                        write(
                                        "POST",
                                        "/orders/preview",
                                        b,
                                        Map.of(
                                                "productId",
                                                p,
                                                "addressId",
                                                ad.path("id").asText(),
                                                "quantity",
                                                1),
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("SHIPPING_UNAVAILABLE");
        call(
                "PUT",
                "/admin/shipping-rules/990000",
                a,
                Map.of("version", 0, "enabled", true, "reason", "Missing money"),
                400);
        write(
                "POST",
                "/admin/products/" + p + "/inventory-adjustments",
                a,
                Map.of("delta", 1.5, "reason", "Fractional quantity"),
                UUID.randomUUID().toString(),
                400);
    }

    @Test
    void evidenceMustBelongToBuyerAndOnlyAuthorizedCaseRelationCanReadBytes() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer"), other = buyer("buyer-active-member");
        var o = paidOrder(a, b, 1);
        String id = o.path("id").asText();
        byte[] bytes = png();
        var ticket =
                call(
                        "POST",
                        "/files/presign",
                        b,
                        Map.of(
                                "name",
                                "evidence.png",
                                "mime",
                                "image/png",
                                "bytes",
                                bytes.length,
                                "purpose",
                                "AFTERSALE_EVIDENCE"),
                        200);
        putBytes(b, ticket, bytes, 200);
        String asset = ticket.path("id").asText();
        call("POST", "/files/" + asset + "/complete", b, Map.of(), 200);
        var c =
                write(
                        "/orders/" + id + "/refund",
                        b,
                        Map.of(
                                "type",
                                "REFUND_ONLY",
                                "reason",
                                "DEV evidence",
                                "evidenceAssetIds",
                                List.of(asset),
                                "requestedGoodsFen",
                                100,
                                "requestedFreightFen",
                                0));
        String cid = c.path("id").asText();
        var res =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get(
                                                "/api/v1/aftersales/"
                                                        + cid
                                                        + "/evidence/"
                                                        + asset
                                                        + "/content")
                                        .header("Authorization", "Bearer " + b))
                        .andReturn()
                        .getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(res.getHeader("Cache-Control")).contains("no-store");
        call("GET", "/aftersales/" + cid + "/evidence/" + asset + "/content", other, null, 404);
        call("GET", "/assets/" + asset + "/content", null, null, 404);
        var oo = paidOrder(a, other, 1);
        write(
                "POST",
                "/orders/" + oo.path("id").asText() + "/refund",
                other,
                Map.of(
                        "type",
                        "REFUND_ONLY",
                        "reason",
                        "Forged evidence",
                        "evidenceAssetIds",
                        List.of(asset),
                        "requestedGoodsFen",
                        100,
                        "requestedFreightFen",
                        0),
                UUID.randomUUID().toString(),
                403);
    }

    @Test
    void aftersaleWindowCanOnlyBeOverriddenByReasonedAdminApplication() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = paidOrder(a, b, 1).path("id").asText();
        jdbc.update(
                "update store_order set"
                    + " order_status='COMPLETED',completed_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 8"
                    + " DAY) where id=?",
                id);
        var request =
                Map.of(
                        "type",
                        "REFUND_ONLY",
                        "reason",
                        "Authorized late exception",
                        "evidenceAssetIds",
                        List.of(),
                        "requestedGoodsFen",
                        100,
                        "requestedFreightFen",
                        0);
        assertThat(
                        write(
                                        "POST",
                                        "/orders/" + id + "/refund",
                                        b,
                                        request,
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("AFTERSALE_WINDOW_CLOSED");
        write("/admin/orders/" + id + "/refund", a, request);
    }
}
