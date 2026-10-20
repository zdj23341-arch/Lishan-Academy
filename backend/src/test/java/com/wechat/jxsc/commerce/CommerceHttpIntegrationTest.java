package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class CommerceHttpIntegrationTest extends CommerceTestSupport {
    @Test
    void addressesAreOwnedEncryptedVersionedAndHaveOneDefault() throws Exception {
        String b = buyer("buyer"), other = buyer("buyer-active-member");
        var a = address(b);
        var second = address(b);
        var list = call("GET", "/addresses", b, null, 200).path("items");
        assertThat(list.findValues("isDefault").stream().filter(JsonNode::asBoolean).count())
                .isEqualTo(1);
        call("PATCH", "/addresses/" + a.path("id").asText(), other, addressBody(false), 404);
        assertThat(
                        jdbc.queryForObject(
                                "select name_cipher from user_address where id=?",
                                String.class,
                                a.path("id").asLong()))
                .doesNotContain("DEV Buyer");
        call("DELETE", "/addresses/" + second.path("id").asText(), b, null, 200);
    }

    @Test
    void quoteRevalidatesExpiryPriceAddressAndRejectsTampering() throws Exception {
        String admin = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(admin, 10, 2000);
        var a = address(b);
        var q = quote(b, p, a.path("id").asText(), 2);
        assertThat(q.path("payableAmountFen").asLong()).isEqualTo(4600);
        write(
                "POST",
                "/orders",
                b,
                Map.of("quoteId", q.path("quoteId").asText(), "priceFen", 1),
                UUID.randomUUID().toString(),
                400);
        write(
                "POST",
                "/orders",
                b,
                Map.of("quoteId", q.path("quoteId").asText(), "attributionToken", "forged"),
                UUID.randomUUID().toString(),
                400);
        jdbc.update(
                "update order_quote set expires_at=DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 MINUTE)"
                        + " where token_hash=?",
                com.wechat.jxsc.auth.TokenCodec.hash(q.path("quoteId").asText()));
        assertThat(
                        write(
                                        "POST",
                                        "/orders",
                                        b,
                                        Map.of("quoteId", q.path("quoteId").asText()),
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("ORDER_STALE");
        q = quote(b, p, a.path("id").asText(), 1);
        jdbc.update("update product set version=version+1,price_fen=3000 where id=?", p);
        assertThat(
                        write(
                                        "POST",
                                        "/orders",
                                        b,
                                        Map.of("quoteId", q.path("quoteId").asText()),
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("ORDER_STALE");
        q = quote(b, p, a.path("id").asText(), 1);
        jdbc.update("update user_address set version=version+1 where id=?", a.path("id").asLong());
        assertThat(
                        write(
                                        "POST",
                                        "/orders",
                                        b,
                                        Map.of("quoteId", q.path("quoteId").asText()),
                                        UUID.randomUUID().toString(),
                                        409)
                                .path("code")
                                .asText())
                .isEqualTo("ORDER_STALE");
    }

    @Test
    void oneUnitTwoBuyersReserveOnceAndCancellationReleasesOnce() throws Exception {
        String admin = admin("SUPER_ADMIN"), a = buyer("buyer"), b = buyer("buyer-active-member");
        String p = book(admin, 1, 2000);
        var qa = quote(a, p, address(a).path("id").asText(), 1);
        var qb = quote(b, p, address(b).path("id").asText(), 1);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var fa =
                    pool.submit(
                            () -> {
                                start.await();
                                return rawCreate(a, qa);
                            });
            var fb =
                    pool.submit(
                            () -> {
                                start.await();
                                return rawCreate(b, qb);
                            });
            start.countDown();
            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertThat(List.of(ra.path("code").asText(), rb.path("code").asText()))
                    .containsExactlyInAnyOrder("OK", "INSUFFICIENT_STOCK");
            var winner = ra.path("code").asText().equals("OK") ? ra : rb;
            String token = winner == ra ? a : b;
            String id = winner.path("data").path("id").asText();
            write("/orders/" + id + "/cancel", token, Map.of());
            write("/orders/" + id + "/cancel", token, Map.of());
            assertThat(
                            jdbc.queryForObject(
                                    "select reserved from book_inventory where product_id=?",
                                    Integer.class,
                                    p))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from inventory_ledger where product_id=? and"
                                            + " event_type='RELEASE'",
                                    Integer.class,
                                    p))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twentyPaymentCallbacksDebitStockOnceAndMismatchIsRecorded() throws Exception {
        String admin = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(admin, 3, 2000);
        var o = order(b, quote(b, p, address(b).path("id").asText(), 1));
        String id = o.path("id").asText();
        for (int n = 0; n < 20; n++)
            write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_ledger where product_id=? and"
                                        + " event_type='PAY'",
                                Integer.class,
                                p))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_task where task_type='ORDER_PAID' and"
                                        + " business_key=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        write(
                "POST",
                "/dev/orders/" + id + "/pay",
                b,
                Map.of("scenario", "AMOUNT_MISMATCH"),
                UUID.randomUUID().toString(),
                409);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from callback_inbox where signature_valid=true and"
                                        + " status='REJECTED'",
                                Integer.class))
                .isPositive();
    }

    @Test
    void unknownPaymentRetainsReservationThenSuccessPreventsCancellation() throws Exception {
        String admin = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(admin, 1, 2000);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "TIMEOUT"));
        write("/orders/" + id + "/cancel", b, Map.of());
        assertThat(
                        jdbc.queryForObject(
                                "select reserved from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(1);
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        write("POST", "/orders/" + id + "/cancel", b, Map.of(), UUID.randomUUID().toString(), 409);
    }

    @Test
    void shippingReceiptAndReturnRefundHaveSeparateStatesAndExactQuantity() throws Exception {
        String admin = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(admin, b, 2);
        String id = o.path("id").asText();
        write(
                "/admin/orders/" + id + "/ship",
                admin,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-100",
                        "reason",
                        "DEV shipment"));
        assertThat(
                        call("GET", "/orders/" + id + "/logistics", b, null, 200)
                                .path("simulated")
                                .asBoolean())
                .isTrue();
        var a =
                write(
                        "/orders/" + id + "/refund",
                        b,
                        Map.of(
                                "type",
                                "RETURN_REFUND",
                                "reason",
                                "DEV return",
                                "evidenceAssetIds",
                                List.of(),
                                "requestedGoodsFen",
                                1000,
                                "requestedFreightFen",
                                0));
        String aid = a.path("id").asText();
        var decision =
                Map.of(
                        "goodsRefundFen",
                        1000,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        1,
                        "returnAddress",
                        "DEV warehouse",
                        "reason",
                        "Approve DEV return");
        write("/admin/aftersales/" + aid + "/preview-decision", admin, decision);
        write("/admin/aftersales/" + aid + "/approve", admin, decision);
        write(
                "POST",
                "/orders/" + id + "/confirm-receipt",
                b,
                Map.of(),
                UUID.randomUUID().toString(),
                409);
        write(
                "/aftersales/" + aid + "/return-shipment",
                b,
                Map.of("carrierCode", "DEV", "trackingNo", "DEV-RETURN"));
        write(
                "/admin/aftersales/" + aid + "/receive-return",
                admin,
                Map.of("returnedQuantity", 1, "reason", "Checked one returned copy"));
        write("/dev/aftersales/" + aid + "/refund", b, Map.of("scenario", "SUCCESS"));
        var finalOrder = call("GET", "/orders/" + id, b, null, 200);
        assertThat(finalOrder.path("refundStatus").asText()).isEqualTo("PARTIAL");
        assertThat(finalOrder.path("fulfillmentStatus").asText()).isEqualTo("SHIPPED");
        assertThat(
                        jdbc.queryForObject(
                                "select sum(on_hand_delta) from inventory_ledger where"
                                        + " order_item_id=? and event_type='RETURN'",
                                Integer.class,
                                finalOrder.path("item").path("id").asLong()))
                .isEqualTo(1);
        write("/orders/" + id + "/confirm-receipt", b, Map.of());
    }
}
