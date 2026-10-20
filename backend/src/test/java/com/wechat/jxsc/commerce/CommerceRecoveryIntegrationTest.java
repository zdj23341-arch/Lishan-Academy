package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.payment.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

class CommerceRecoveryIntegrationTest extends CommerceTestSupport {
    @Autowired TaskWorker worker;
    @Autowired TaskQueue queue;
    @Autowired PlatformTransactionManager manager;
    @Autowired PaymentInbox inbox;
    @Autowired PaymentTransactions payments;

    @Test
    void latePaidAfterCloseRecoversStockOrRefundsPermanentlyWithoutLaterDelivery()
            throws Exception {
        String admin = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(admin, 1, 2000);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "CLOSED"));
        write("/orders/" + id + "/cancel", b, Map.of());
        write(
                "/admin/products/" + p + "/inventory-adjustments",
                admin,
                Map.of("delta", -1, "reason", "DEV unavailable stock"));
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=? and"
                                        + " exceptional=true",
                                Integer.class,
                                id))
                .isEqualTo(1);
        write(
                "/admin/products/" + p + "/inventory-adjustments",
                admin,
                Map.of("delta", 1, "reason", "DEV stock replenished"));
        long pid =
                jdbc.queryForObject(
                        "select id from payment_transaction where order_id=?", Long.class, id);
        var i = payments.payment(pid);
        var event =
                new PaymentProvider.VerifiedEvent(
                        "DEV",
                        "DEV-new-notification-" + UUID.randomUUID(),
                        "PAYMENT",
                        pid,
                        Long.parseLong(id),
                        i.businessNo(),
                        "DEV-" + i.businessNo(),
                        i.amountFen(),
                        "CNY",
                        "DEV",
                        "DEV-MERCHANT",
                        PaymentProvider.State.SUCCEEDED,
                        true,
                        "DEV signed repeat");
        assertThat(inbox.process(inbox.record(event), event)).isNull();
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(1);
    }

    @Test
    void latePaidAfterCloseWithStockRestoresAndDebitsExactlyOnce() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 1, 2000);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "CLOSED"));
        write("/orders/" + id + "/cancel", b, Map.of());
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isZero();
    }

    @Test
    void additionalSuccessfulTransactionUsesIdempotencyAndCreatesOneExceptionalRefund()
            throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = paidOrder(a, b, 1).path("id").asText();
        String key = UUID.randomUUID().toString();
        write(
                "POST",
                "/dev/orders/" + id + "/pay",
                b,
                Map.of("scenario", "EXTRA_SUCCESS"),
                key,
                200);
        write(
                "POST",
                "/dev/orders/" + id + "/pay",
                b,
                Map.of("scenario", "EXTRA_SUCCESS"),
                key,
                200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=? and"
                                        + " exceptional=true",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("paidAmountFen").asLong())
                .isEqualTo(5200);
    }

    @Test
    void refundUnknownAndDuplicateSuccessPreserveMoneyAndRestockOnlyExplicitQuantity()
            throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var o = paidOrder(a, b, 2);
        String id = o.path("id").asText();
        String p = o.path("item").path("productId").asText();
        var c =
                write(
                        "/orders/" + id + "/refund",
                        b,
                        Map.of(
                                "type",
                                "REFUND_ONLY",
                                "reason",
                                "DEV compensation",
                                "evidenceAssetIds",
                                List.of(),
                                "requestedGoodsFen",
                                1000,
                                "requestedFreightFen",
                                0));
        String cid = c.path("id").asText();
        write(
                "/admin/aftersales/" + cid + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        1000,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "Price compensation"));
        write("/dev/aftersales/" + cid + "/refund", b, Map.of("scenario", "TIMEOUT"));
        assertThat(call("GET", "/aftersales/" + cid, b, null, 200).path("status").asText())
                .isEqualTo("PROCESSING");
        write("/dev/aftersales/" + cid + "/refund", b, Map.of("scenario", "SUCCESS"));
        write("/dev/aftersales/" + cid + "/refund", b, Map.of("scenario", "SUCCESS"));
        write("/dev/aftersales/" + cid + "/refund", b, Map.of("scenario", "FAILURE"));
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("refundedGoodsFen").asLong())
                .isEqualTo(1000);
        assertThat(
                        jdbc.queryForObject(
                                "select on_hand from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(5);
    }

    @Test
    void leasesRecoverAfterWorkerCrashAndAnOldLeaseCannotFinishNewOwnerWork() {
        jdbc.update("update outbox_task set next_run_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)");
        String key = UUID.randomUUID().toString();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        t -> queue.enqueue("LEASE_TEST", key, "{}", Instant.now().minusSeconds(1)));
        var first = queue.claim();
        assertThat(first).isNotNull();
        assertThat(queue.claim()).isNull();
        jdbc.update(
                "update outbox_task set locked_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)"
                        + " where id=?",
                first.id());
        var second = queue.claim();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.token()).isNotEqualTo(first.token());
        queue.finish(first, true, null, Duration.ZERO);
        assertThat(
                        jdbc.queryForObject(
                                "select status from outbox_task where id=?",
                                String.class,
                                first.id()))
                .isEqualTo("RUNNING");
        queue.finish(second, true, null, Duration.ZERO);
    }

    @Test
    void digitalZeroPriceUsesNoChargeAndCompletesAfterRealEntitlementConsumer() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        var p = createProduct(a, "COURSE", "DEV no charge " + UUID.randomUUID(), 0);
        String pid = p.path("id").asText();
        call(
                "POST",
                "/admin/courses",
                a,
                Map.of("version", 0, "productId", pid, "validityType", "DAYS", "validityDays", 30),
                200);
        publish(a, p);
        var q = write("/orders/preview", b, Map.of("productId", pid, "quantity", 1));
        var o = order(b, q);
        String id = o.path("id").asText();
        assertThat(o.path("channel").asText()).isEqualTo("NO_CHARGE");
        assertThat(o.path("orderStatus").asText()).isEqualTo("PAID");
        worker.run(30);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_transaction where order_id=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from outbox_task where"
                                        + " task_type='ENTITLEMENT_DELIVER' and business_key=?",
                                String.class,
                                id))
                .isEqualTo("DONE");
    }

    @Test
    void expiryUsesTheSameCloseAndUnknownRecoveryPath() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 1, 2000);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "TIMEOUT"));
        jdbc.update("update outbox_task set next_run_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)");
        jdbc.update(
                "update store_order set expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)"
                        + " where id=?",
                id);
        jdbc.update(
                "update outbox_task set next_run_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)"
                        + " where task_type='ORDER_EXPIRE' and business_key=?",
                id);
        worker.run(2);
        assertThat(
                        jdbc.queryForObject(
                                "select reserved from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(1);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("PENDING_PAYMENT");
    }
}
