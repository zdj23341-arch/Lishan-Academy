package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.content.ContentHttpSupport;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.*;
import java.util.concurrent.*;

abstract class CommerceTestSupport extends ContentHttpSupport {
    JsonNode write(String method, String path, String token, Object body, String key, int status)
            throws Exception {
        var req =
                MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key);
        if (body != null) req.content(json.writeValueAsBytes(body));
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(path + " " + res.getContentAsString()).isEqualTo(status);
        var result = json.readTree(res.getContentAsByteArray());
        return status == 200 ? result.path("data") : result;
    }

    JsonNode write(String path, String token, Object body) throws Exception {
        return write("POST", path, token, body, UUID.randomUUID().toString(), 200);
    }

    Map<String, Object> addressBody(boolean def) {
        return Map.of(
                "version",
                0,
                "name",
                "DEV Buyer",
                "phone",
                "13800000000",
                "provinceCode",
                "310000",
                "cityCode",
                "310100",
                "districtCode",
                "310101",
                "detail",
                "DEV test address 1",
                "isDefault",
                def);
    }

    JsonNode address(String token) throws Exception {
        return call("POST", "/addresses", token, addressBody(true), 200);
    }

    String book(String admin, int stock, long price) throws Exception {
        var p =
                publish(
                        admin,
                        createProduct(admin, "BOOK", "DEV Commerce " + UUID.randomUUID(), price));
        write(
                "/admin/products/" + p.path("id").asText() + "/inventory-adjustments",
                admin,
                Map.of("delta", stock, "reason", "DEV initial stock"));
        call(
                "PUT",
                "/admin/shipping-rules/310000",
                admin,
                Map.of(
                        "version",
                        0,
                        "enabled",
                        true,
                        "freightFen",
                        600,
                        "freeThresholdFen",
                        10000,
                        "reason",
                        "DEV shipping"),
                200);
        return p.path("id").asText();
    }

    JsonNode quote(String buyer, String p, String a, int qty) throws Exception {
        return write(
                "/orders/preview", buyer, Map.of("productId", p, "addressId", a, "quantity", qty));
    }

    JsonNode order(String buyer, JsonNode q) throws Exception {
        return write("/orders", buyer, Map.of("quoteId", q.path("quoteId").asText()));
    }

    JsonNode paidOrder(String admin, String buyer, int qty) throws Exception {
        String p = book(admin, qty + 5, 2000);
        var a = address(buyer);
        var o = order(buyer, quote(buyer, p, a.path("id").asText(), qty));
        write(
                "/dev/orders/" + o.path("id").asText() + "/pay",
                buyer,
                Map.of("scenario", "SUCCESS"));
        return call("GET", "/orders/" + o.path("id").asText(), buyer, null, 200);
    }

    JsonNode rawCreate(String token, JsonNode q) throws Exception {
        var res =
                mvc.perform(
                                MockMvcRequestBuilders.post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsBytes(
                                                        Map.of(
                                                                "quoteId",
                                                                q.path("quoteId").asText()))))
                        .andReturn()
                        .getResponse();
        return json.readTree(res.getContentAsByteArray());
    }
}
