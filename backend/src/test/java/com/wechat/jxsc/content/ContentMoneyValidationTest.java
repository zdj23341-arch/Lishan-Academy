package com.wechat.jxsc.content;

import org.junit.jupiter.api.Test;

import java.util.*;

class ContentMoneyValidationTest extends ContentHttpSupport {
    @Test
    void fractionalFenAndMissingPriceCannotBecomeAChangedOrFreeProduct() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var body = product("BOOK", "Exact fen", 100);
        body.put("priceFen", 100.9);
        call("POST", "/admin/products", token, body, 400);
        body.put("priceFen", "100");
        call("POST", "/admin/products", token, body, 400);
    }

    @Test
    void missingPriceCannotCreateAFreeProduct() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var body = product("BOOK", "Required price", 100);
        body.remove("priceFen");
        call("POST", "/admin/products", token, body, 400);
        body.put("priceFen", null);
        call("POST", "/admin/products", token, body, 400);
    }

    @Test
    void administratorsCanDiscoverAndEditDraftMembershipPlans() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var p = createProduct(token, "MEMBERSHIP", "Plan terms", 600);
        var plan = p.path("membership");
        org.assertj.core.api.Assertions.assertThat(plan.path("id").asText()).isNotBlank();
        call(
                "PATCH",
                "/admin/membership/plans/" + plan.path("id").asText(),
                token,
                Map.of(
                        "version",
                        plan.path("version").asLong(),
                        "durationMonths",
                        12,
                        "status",
                        "DISABLED"),
                200);
        call(
                "PATCH",
                "/admin/membership/plans/" + plan.path("id").asText(),
                token,
                Map.of(
                        "version",
                        plan.path("version").asLong(),
                        "durationMonths",
                        6,
                        "status",
                        "DISABLED"),
                409);
    }
}
