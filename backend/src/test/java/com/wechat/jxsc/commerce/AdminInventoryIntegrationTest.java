package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;

class AdminInventoryIntegrationTest extends CommerceTestSupport {
    @org.springframework.beans.factory.annotation.Autowired
    com.wechat.jxsc.common.commerce.ProtectedOperationResults protectedResults;

    @Test
    void historicalUnversionedOperationStillReplaysWithoutInventingVersion() throws Exception {
        String admin = admin("SUPER_ADMIN");
        String id = createProduct(admin, "BOOK", "legacy-" + UUID.randomUUID(), 100).path("id").asText();
        String path = "/admin/products/" + id + "/inventory-adjustments", key = UUID.randomUUID().toString();
        var body = Map.of("delta", 2, "reason", "legacy");
        var first = write("POST", path, admin, body, key, 200);
        var historical = first.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) historical).remove("version");
        String oldHash = com.wechat.jxsc.auth.TokenCodec.hash(
                "{\"productId\":" + id + ",\"request\":{\"delta\":2,\"reason\":\"legacy\"}}");
        assertThat(jdbc.update("update idempotency_record set request_hash=?,response_json=? where scope='INVENTORY_ADJUST' and operation_key=?",
                oldHash, protectedResults.protect(historical).toString(), key)).isEqualTo(1);
        var replay = write("POST", path, admin, body, key, 200);
        assertThat(replay.path("version").isNull()).isTrue();
        assertThat(replay.path("onHand").asInt()).isEqualTo(2);
        var explicitNull = new LinkedHashMap<String, Object>(body);
        explicitNull.put("version", null);
        assertThat(write("POST", path, admin, explicitNull, key, 200)).isEqualTo(replay);
        stock(admin, id, 2, 0, 2);
        assertThat(jdbc.queryForObject("select count(*) from inventory_ledger where product_id=? and event_type='ADJUST'", Integer.class, id)).isEqualTo(1);
    }
    @Test
    void bookProjectionAndInventoryReadsReturnActualCountsAndIndependentVersion() throws Exception {
        String admin = admin("SUPER_ADMIN"), operator = admin("ORDER_OPERATOR");
        String key = "inventory-" + UUID.randomUUID();
        var p = createProduct(admin, "BOOK", key + " book", 100);
        createProduct(admin, "COURSE", key + " course", 100);
        createProduct(admin, "MEMBERSHIP", key + " member", 100);
        String id = p.path("id").asText();
        write("/admin/products/" + id + "/inventory-adjustments", admin, Map.of("delta", 9, "reason", "initial"));
        var product = call("GET", "/admin/products/" + id, admin, null, 200);
        assertThat(product.path("book").path("physicalStock").asInt(-1)).isEqualTo(9);
        assertThat(product.path("book").path("lockedStock").asInt(-1)).isZero();
        assertThat(product.path("book").path("availableStock").asInt(-1)).isEqualTo(9);
        assertThat(product.path("book").path("inventoryVersion").asLong(-1)).isEqualTo(1);
        var list = call("GET", "/admin/inventory?keyword=" + key + "&pageSize=1", operator, null, 200);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("productId").asText()).isEqualTo(id);
        assertThat(list.path("items").get(0).path("type").asText()).isEqualTo("BOOK");
        stock(operator, id, 9, 0, 9);
        var filtered = call("GET", "/admin/products?type=BOOK&keyword=" + key, admin, null, 200);
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("book")).isEqualTo(product.path("book"));
        call("GET", "/admin/inventory", null, null, 401);
        call("GET", "/admin/inventory", admin("CONTENT_EDITOR"), null, 403);
        call("GET", "/admin/products/" + id + "/inventory", admin("CONTENT_EDITOR"), null, 403);
        call("GET", "/admin/inventory?pageSize=101", operator, null, 400);
        call("GET", "/admin/inventory?keyword=" + "x".repeat(201), operator, null, 400);
        var beyond = call("GET", "/admin/inventory?keyword=" + key + "&page=2&pageSize=1", operator, null, 200);
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("total").asInt()).isEqualTo(1);
    }

    @Test
    void realCheckoutReserveCancelAndAdjustmentAreVisibleAndNeverConsumeLockedStock() throws Exception {
        String admin = admin("SUPER_ADMIN"), buyer = buyer("buyer");
        String id = book(admin, 10, 1000);
        var initial = stock(admin, id, 10, 0, 10);
        var order = order(buyer, quote(buyer, id, address(buyer).path("id").asText(), 3));
        var reserved = stock(admin, id, 10, 3, 7);
        assertThat(reserved.path("version").asLong()).isGreaterThan(initial.path("version").asLong());
        var fail = write("POST", "/admin/products/" + id + "/inventory-adjustments", admin,
                Map.of("delta", -8, "reason", "cannot consume reserved", "version", reserved.path("version").asLong()),
                UUID.randomUUID().toString(), 409);
        assertThat(fail.path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        stock(admin, id, 10, 3, 7);
        write("/orders/" + order.path("id").asText() + "/cancel", buyer, Map.of());
        var released = stock(admin, id, 10, 0, 10);
        assertThat(released.path("version").asLong()).isGreaterThan(reserved.path("version").asLong());
    }

    @Test
    void staleAdjustmentRejectsWithoutLedgerChangeAndOriginalKeyReplaysOnce() throws Exception {
        String admin = admin("SUPER_ADMIN");
        String id = createProduct(admin, "BOOK", "version-" + UUID.randomUUID(), 100).path("id").asText();
        String path = "/admin/products/" + id + "/inventory-adjustments", key = UUID.randomUUID().toString();
        var request = Map.of("delta", 4, "reason", "initial", "version", 0);
        var first = write("POST", path, admin, request, key, 200);
        assertThat(first.path("version").asLong(-1)).isEqualTo(1);
        assertThat(write("POST", path, admin, request, key, 200)).isEqualTo(first);
        var failed = write("POST", path, admin, Map.of("delta", 2, "reason", "stale", "version", 0),
                UUID.randomUUID().toString(), 409);
        assertThat(failed.path("code").asText()).isEqualTo("VERSION_CONFLICT");
        stock(admin, id, 4, 0, 4);
        assertThat(jdbc.queryForObject("select count(*) from inventory_ledger where product_id=? and event_type='ADJUST'", Integer.class, id)).isEqualTo(1);
        var current = write("POST", path, admin, Map.of("delta", -1, "reason", "current", "version", 1), UUID.randomUUID().toString(), 200);
        assertThat(current.path("version").asLong()).isEqualTo(2);
        stock(admin, id, 3, 0, 3);
        write("POST", path, admin, Map.of("delta", 1, "reason", "legacy atomic delta"), UUID.randomUUID().toString(), 200);
        stock(admin, id, 4, 0, 4);
        write("POST", path, admin, Map.of("delta", 1, "reason", "negative version", "version", -1), UUID.randomUUID().toString(), 400);
    }

    @Test
    void nonBooksHaveNoInventoryAndCannotBeAdjusted() throws Exception {
        String admin = admin("SUPER_ADMIN");
        for (String type : List.of("COURSE", "MEMBERSHIP")) {
            var p = createProduct(admin, type, "not-book-" + UUID.randomUUID(), 100);
            String id = p.path("id").asText();
            assertThat(p.path("book").isNull()).isTrue();
            call("GET", "/admin/products/" + id + "/inventory", admin, null, 404);
            write("POST", "/admin/products/" + id + "/inventory-adjustments", admin,
                    Map.of("delta", 1, "reason", "non book", "version", 0), UUID.randomUUID().toString(), 404);
            assertThat(jdbc.queryForObject("select count(*) from inventory_ledger where product_id=?", Integer.class, id)).isZero();
        }
    }

    @Test
    void concurrentSameVersionAdjustmentsHaveOnlyOneWinner() throws Exception {
        String admin = admin("SUPER_ADMIN");
        String id = createProduct(admin, "BOOK", "concurrent-" + UUID.randomUUID(), 100).path("id").asText();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/admin/products/" + id + "/inventory-adjustments")
                        .header("Authorization", "Bearer " + admin).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("delta", 1, "reason", "concurrent", "version", 0))))
                        .andReturn().getResponse().getStatus();
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            stock(admin, id, 1, 0, 1);
        } finally { pool.shutdownNow(); }
    }

    private JsonNode stock(String token, String id, int onHand, int reserved, int available) throws Exception {
        var value = call("GET", "/admin/products/" + id + "/inventory", token, null, 200);
        assertThat(value.path("onHand").asInt(-1)).isEqualTo(onHand);
        assertThat(value.path("reserved").asInt(-1)).isEqualTo(reserved);
        assertThat(value.path("availableStock").asInt(-1)).isEqualTo(available);
        assertThat(value.path("version").isIntegralNumber()).isTrue();
        return value;
    }
}
