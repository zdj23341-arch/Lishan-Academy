package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.wechat.jxsc.payment.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

@TestPropertySource(properties = "jxsc.privacy.retention-days=365")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class FinalLatePaymentIntegrationTest extends EntitlementTestSupport {
    @org.junit.jupiter.api.Test
    void duplicateOfPreviouslyFulfilledPaymentAfterClosureDoesNotCreateRefund() throws Exception {
        when(provider.channel()).thenReturn("DEV");
        when(provider.prepay(any()))
                .thenAnswer(x -> result(x.getArgument(0), PaymentProvider.State.SUCCEEDED));
        String product = book(a, 3, 2000);
        String id =
                order(b, quote(b, product, address(b).path("id").asText(), 1)).path("id").asText();
        write("/payments/wechat/prepay", b, Map.of("orderId", id));
        jdbc.update(
                "update store_order set"
                    + " order_status='COMPLETED',fulfillment_status='RECEIVED',completed_at=UTC_TIMESTAMP(6)"
                    + " where id=?",
                id);
        long pid =
                jdbc.queryForObject(
                        "select id from payment_transaction where order_id=?", Long.class, id);
        var intent = payments.payment(pid);
        String channelId =
                jdbc.queryForObject(
                        "select channel_txn_id from payment_transaction where id=?",
                        String.class,
                        pid);
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        var event =
                new PaymentProvider.VerifiedEvent(
                        intent.channel(),
                        "duplicate-closed-" + UUID.randomUUID(),
                        "PAYMENT",
                        pid,
                        intent.orderId(),
                        intent.businessNo(),
                        channelId,
                        intent.amountFen(),
                        intent.currency(),
                        intent.appId(),
                        intent.merchantId(),
                        PaymentProvider.State.SUCCEEDED,
                        true,
                        "Duplicate original success");
        inbox.process(inbox.record(event), event);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select order_status from store_order where id=?",
                                String.class,
                                id))
                .isEqualTo("COMPLETED");
    }

    @MockitoBean PaymentProvider provider;
    @Autowired PaymentTransactions payments;
    @Autowired PaymentInbox inbox;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    PaymentProvider.Result result(PaymentProvider.Intent i, PaymentProvider.State state) {
        return new PaymentProvider.Result(
                state,
                "DEV-LATE-" + i.businessNo(),
                i.amountFen(),
                i.currency(),
                i.appId(),
                i.merchantId(),
                Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closureWinningQueryRaceRefundsOriginalPaymentWithoutRestartingFulfillment(boolean digital)
            throws Exception {
        when(provider.channel()).thenReturn("DEV");
        when(provider.prepay(any()))
                .thenAnswer(x -> result(x.getArgument(0), PaymentProvider.State.PROCESSING));
        when(provider.query(any()))
                .thenAnswer(x -> result(x.getArgument(0), PaymentProvider.State.CLOSED));
        String product =
                digital ? course("PERMANENT", null).path("productId").asText() : book(a, 3, 2000);
        var q =
                digital
                        ? write("/orders/preview", b, Map.of("productId", product, "quantity", 1))
                        : quote(b, product, address(b).path("id").asText(), 1);
        String id = order(b, q).path("id").asText();
        write("/payments/wechat/prepay", b, Map.of("orderId", id));
        write("/orders/" + id + "/cancel", b, Map.of());
        long pid =
                jdbc.queryForObject(
                        "select id from payment_transaction where order_id=?", Long.class, id);
        var intent = payments.payment(pid);
        var event =
                new PaymentProvider.VerifiedEvent(
                        intent.channel(),
                        "late-" + UUID.randomUUID(),
                        "PAYMENT",
                        pid,
                        intent.orderId(),
                        intent.businessNo(),
                        "DEV-LATE-" + intent.businessNo(),
                        intent.amountFen(),
                        intent.currency(),
                        intent.appId(),
                        intent.merchantId(),
                        PaymentProvider.State.SUCCEEDED,
                        true,
                        "Synthetic verified success");
        long row = inbox.record(event);
        var snapshot = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var confirmation =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    tx -> {
                                                        // Establish a stale RR snapshot before
                                                        // closure. Query completion must use a
                                                        // current
                                                        // closure read under the order lock rather
                                                        // than this old snapshot.
                                                        jdbc.queryForObject(
                                                                "select count(*) from"
                                                                        + " account_closure",
                                                                Long.class);
                                                        snapshot.countDown();
                                                        try {
                                                            if (!closed.await(10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "closure timeout");
                                                        } catch (InterruptedException e) {
                                                            throw new IllegalStateException(e);
                                                        }
                                                        inbox.process(row, event);
                                                        em.flush();
                                                        assertThat(
                                                                        inbox.confirmPaymentQuery(
                                                                                row,
                                                                                intent,
                                                                                result(
                                                                                        intent,
                                                                                        PaymentProvider
                                                                                                .State
                                                                                                .UNKNOWN)))
                                                                .isFalse();
                                                        assertThat(
                                                                        jdbc.queryForObject(
                                                                                "select count(*)"
                                                                                    + " from"
                                                                                    + " refund_transaction"
                                                                                    + " where"
                                                                                    + " order_id=?",
                                                                                Long.class,
                                                                                id))
                                                                .isZero();
                                                        inbox.confirmPaymentQuery(
                                                                row,
                                                                intent,
                                                                result(
                                                                        intent,
                                                                        PaymentProvider.State
                                                                                .SUCCEEDED));
                                                        em.flush();
                                                        inbox.confirmPaymentQuery(
                                                                row,
                                                                intent,
                                                                result(
                                                                        intent,
                                                                        PaymentProvider.State
                                                                                .SUCCEEDED));
                                                        inbox.process(row, event);
                                                        return null;
                                                    }));
            assertThat(snapshot.await(10, TimeUnit.SECONDS)).isTrue();
            write(
                    "/me/close",
                    b,
                    Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
            closed.countDown();
            confirmation.get(20, TimeUnit.SECONDS);
        } finally {
            closed.countDown();
        }
        assertThat(
                        jdbc.queryForObject(
                                "select order_status from store_order where id=?",
                                String.class,
                                id))
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_task where business_key=? and"
                                        + " task_type in ('ORDER_PAID','ENTITLEMENT_DELIVER')",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=? and"
                                    + " payment_id=? and exceptional=true and state<>'SUCCEEDED'",
                                Long.class,
                                id,
                                pid))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_ledger where order_item_id=(select"
                                        + " id from order_item where order_id=?) and event_type in"
                                        + " ('PAY','RECOVER')",
                                Long.class,
                                id))
                .isZero();
    }
}
