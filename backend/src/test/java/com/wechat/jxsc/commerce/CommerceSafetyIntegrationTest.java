package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.tasks.TaskWorker;
import com.wechat.jxsc.payment.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

class CommerceSafetyIntegrationTest extends CommerceTestSupport {
    @Test
    void fullRefundBlocksFurtherReceiptAndRetainsHistoricalCompletionTime() throws Exception {
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
                        "DEV",
                        "trackingNo",
                        "DEV-FULL",
                        "reason",
                        "DEV full refund test"));
        String cid =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Full compensation",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        2000,
                                        "requestedFreightFen",
                                        600))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + cid + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        2000,
                        "freightRefundFen",
                        600,
                        "returnedQuantity",
                        0,
                        "reason",
                        "Approved full compensation"));
        write("/dev/aftersales/" + cid + "/refund", b, Map.of("scenario", "SUCCESS"));
        write(
                "POST",
                "/orders/" + id + "/confirm-receipt",
                b,
                Map.of(),
                UUID.randomUUID().toString(),
                409);
        var current = call("GET", "/orders/" + id, b, null, 200);
        assertThat(current.path("completedAt").isNull()).isTrue();
        assertThat(current.path("allowedActions").toString()).doesNotContain("CONFIRM_RECEIPT");
    }

    @Autowired PaymentInbox inbox;
    @Autowired PaymentTransactions payments;
    @Autowired TaskWorker worker;

    @Test
    void actualWarehouseReceiptRestocksEvenWhenRefundFailsAndNeverRestocksTwice() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(a, b, 2);
        String id = o.path("id").asText(), p = o.path("item").path("productId").asText();
        write(
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-RETURN-SAFETY",
                        "reason",
                        "DEV ship"));
        String cid =
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
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + cid + "/approve",
                a,
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
                        "One copy received"));
        write(
                "/aftersales/" + cid + "/return-shipment",
                b,
                Map.of("carrierCode", "DEV", "trackingNo", "DEV-RETURN-ONE"));
        String key = UUID.randomUUID().toString();
        var receipt = Map.of("returnedQuantity", 1, "reason", "Inspected physical return");
        write("POST", "/admin/aftersales/" + cid + "/receive-return", a, receipt, key, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(6);
        write("POST", "/admin/aftersales/" + cid + "/receive-return", a, receipt, key, 200);
        write("/dev/aftersales/" + cid + "/refund", b, Map.of("scenario", "FAILURE"));
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(6);
    }

    @Test
    void invalidSignatureAndUnknownResourceAreDurablyRejectedBeforeAnyOrderLookup() {
        var event =
                new PaymentProvider.VerifiedEvent(
                        "DEV",
                        "DEV-invalid-" + UUID.randomUUID(),
                        "PAYMENT",
                        99999999,
                        99999999,
                        "DEV-UNKNOWN",
                        "DEV-UNKNOWN",
                        1,
                        "CNY",
                        "DEV",
                        "DEV-MERCHANT",
                        PaymentProvider.State.SUCCEEDED,
                        false,
                        "DEV invalid signature");
        long row = inbox.record(event);
        assertThat(inbox.process(row, event)).isEqualTo("INVALID_SIGNATURE");
        assertThat(
                        jdbc.queryForObject(
                                "select status from callback_inbox where id=?", String.class, row))
                .isEqualTo("REJECTED");
    }

    @Test
    void paymentAndCancelRaceConvergesToOnePaidDebit() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 1, 2000),
                id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/payments/wechat/prepay", b, Map.of("orderId", id));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var pay =
                    pool.submit(
                            () -> {
                                start.await();
                                return raw(
                                        "/dev/orders/" + id + "/pay",
                                        b,
                                        Map.of("scenario", "SUCCESS"));
                            });
            var cancel =
                    pool.submit(
                            () -> {
                                start.await();
                                return raw("/orders/" + id + "/cancel", b, Map.of());
                            });
            start.countDown();
            assertThat(pay.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(cancel.get(20, TimeUnit.SECONDS)).isIn(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select reserved from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isZero();
    }

    private int raw(String path, String token, Object body) throws Exception {
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/v1" + path)
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(body)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void fifteenDayAutoReceiveWaitsForAftersaleAndThenCompletes() throws Exception {
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
                        "DEV",
                        "trackingNo",
                        "DEV-AUTO",
                        "reason",
                        "DEV auto receive"));
        String cid =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Pause receipt",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        100,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        jdbc.update("update outbox_task set next_run_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)");
        jdbc.update(
                "update shipment set shipped_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 16 DAY) where"
                    + " order_id=?",
                id);
        jdbc.update(
                "update outbox_task set next_run_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)"
                    + " where task_type='AUTO_RECEIVE' and business_key=?",
                id);
        worker.run(1);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("PAID");
        write(
                "/admin/aftersales/" + cid + "/reject",
                a,
                Map.of("reason", "Case resolved without refund"));
        jdbc.update(
                "update outbox_task set next_run_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) where"
                    + " task_type='AUTO_RECEIVE' and business_key=?",
                id);
        worker.run(1);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("COMPLETED");
    }
}
