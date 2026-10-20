package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.tasks.TaskHandler;
import com.wechat.jxsc.payment.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class CommerceReviewReconciliationIntegrationTest extends CommerceTestSupport {
    @Autowired com.wechat.jxsc.common.tasks.TaskWorker worker;

    @Test
    void authenticatedDoubleRefundResolutionBooksOnceAndRestocksOnce() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.SUCCEEDED);
        long old = refund(approve(a, b, id));
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED))
                .when(provider)
                .refund(any());
        workflow.syncRefund(old);
        long newer = refund(approve(a, b, id));
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.PROCESSING))
                .when(provider)
                .refund(any());
        workflow.syncRefund(newer);
        var event = event(transactions.refund(old), "REFUND");
        inbox.process(inbox.record(event), event);
        var body =
                Map.of(
                        "reason",
                        "Verified original-number evidence; recognize duplicate payout loss");
        write(
                "POST",
                "/admin/orders/" + id + "/reconcile",
                a,
                body,
                UUID.randomUUID().toString(),
                409);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.SUCCEEDED))
                .when(provider)
                .queryRefund(any());
        workflow.handle("REFUND_RECONCILE", Long.toString(old), "{}");
        workflow.handle("REFUND_RECONCILE", Long.toString(newer), "{}");
        write("/admin/orders/" + id + "/reconcile", a, body);
        write("/admin/orders/" + id + "/reconcile", a, body);
        worker.run(100);
        var now = current(id, b);
        assertThat(now.path("financialHold").asBoolean()).isFalse();
        assertThat(now.path("refundedGoodsFen").asLong()).isEqualTo(2000);
        assertThat(
                        jdbc.queryForObject(
                                "select sum(excess_fen) from economic_resolution where order_id=?",
                                Long.class,
                                id))
                .isEqualTo(2600);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from economic_resolution where order_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select state from refund_transaction where id=?",
                                String.class,
                                old))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select restored_quantity from order_item where order_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        var repeated = event(transactions.refund(old), "REFUND");
        inbox.process(inbox.record(repeated), repeated);
        assertThat(current(id, b).path("financialHold").asBoolean()).isFalse();
        // Analytics fixture classification only; no real channel or money is contacted.
        jdbc.update("update store_order set channel='WECHAT_PAY' where id=?", id);
        jdbc.update("update payment_transaction set channel='WECHAT_PAY' where order_id=?", id);
        jdbc.update("update refund_transaction set channel='WECHAT_PAY' where order_id=?", id);
        var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var metrics =
                call(
                        "GET",
                        "/admin/analytics/overview?startDate=" + date + "&endDate=" + date,
                        a,
                        null,
                        200);
        assertThat(metrics.path("refundsFen").asLong()).isEqualTo(5200);
    }

    @MockitoBean PaymentProvider provider;
    @Autowired PaymentInbox inbox;
    @Autowired PaymentTransactions transactions;
    @Autowired PaymentWorkflow workflow;
    @Autowired RefundTransactions refundTransactions;
    @Autowired com.wechat.jxsc.order.CommerceEventDelivery delivery;

    private PaymentProvider.Result result(PaymentProvider.Intent i, PaymentProvider.State state) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return new PaymentProvider.Result(
                state,
                "DEV-Q-" + i.businessNo(),
                i.amountFen(),
                i.currency(),
                i.appId(),
                i.merchantId(),
                Map.of());
    }

    private PaymentProvider.VerifiedEvent event(PaymentProvider.Intent i, String kind) {
        return new PaymentProvider.VerifiedEvent(
                i.channel(),
                "review-" + UUID.randomUUID(),
                kind,
                i.id(),
                i.orderId(),
                i.businessNo(),
                "DEV-Q-" + i.businessNo(),
                i.amountFen(),
                i.currency(),
                i.appId(),
                i.merchantId(),
                PaymentProvider.State.SUCCEEDED,
                true,
                "Verified distinct channel notification");
    }

    private String start(String a, String b, int stock, PaymentProvider.State prepay)
            throws Exception {
        when(provider.channel()).thenReturn("DEV");
        when(provider.prepay(any())).thenAnswer(x -> result(x.getArgument(0), prepay));
        String p = book(a, stock, 2000);
        String id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/payments/wechat/prepay", b, Map.of("orderId", id));
        return id;
    }

    private String approve(String a, String b, String id) throws Exception {
        String c =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Reviewed refund",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        2000,
                                        "requestedFreightFen",
                                        600))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + c + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        2000,
                        "freightRefundFen",
                        600,
                        "returnedQuantity",
                        1,
                        "reason",
                        "Full refund with physical restoration"));
        return c;
    }

    private long refund(String caseId) {
        return jdbc.queryForObject(
                "select id from refund_transaction where aftersale_id=?", Long.class, caseId);
    }

    private JsonNode current(String id, String b) throws Exception {
        return call("GET", "/orders/" + id, b, null, 200);
    }

    @Test
    void failedRefundLateSuccessHoldsNewUnsentRefundAndPreservesItsCase() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.SUCCEEDED);
        String oldCase = approve(a, b, id);
        long old = refund(oldCase);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED))
                .when(provider)
                .refund(any());
        workflow.syncRefund(old);
        String newer = approve(a, b, id);
        var success = event(transactions.refund(old), "REFUND");
        long evidence = inbox.record(success);
        assertThat(inbox.process(evidence, success)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select active_aftersale_id from store_order where id=?",
                                Long.class,
                                id))
                .isEqualTo(Long.parseLong(newer));
        assertThat(current(id, b).path("financialHold").asBoolean()).isTrue();
        assertThat(current(id, b).path("refundedGoodsFen").asLong()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select state from refund_transaction where id=?",
                                String.class,
                                old))
                .isEqualTo("FAILED");
        clearInvocations(provider);
        assertThatThrownBy(() -> workflow.syncRefund(refund(newer)))
                .isInstanceOf(TaskHandler.RetryLater.class);
        workflow.handle("REFUND_RECONCILE", Long.toString(refund(newer)), "{}");
        verify(provider, never()).refund(any());
        verify(provider, never()).queryRefund(any());
        var now = current(id, b);
        write(
                "POST",
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        now.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-HOLD",
                        "reason",
                        "Must wait"),
                UUID.randomUUID().toString(),
                409);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.UNKNOWN))
                .when(provider)
                .queryRefund(any());
        assertThatThrownBy(() -> workflow.handle("REFUND_RECONCILE", Long.toString(old), "{}"))
                .isInstanceOf(TaskHandler.RetryLater.class);
        assertThat(inbox.process(evidence, success)).isNull();
        assertThat(current(id, b).path("reconciliation").path("confirmedRefundFen").asLong())
                .isEqualTo(2600);
        assertThat(current(id, b).path("reconciliation").path("possibleInFlightRefundFen").asLong())
                .isZero();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update reconciliation_query set source='CHANGED' where"
                                                + " reconciliation_id=(select id from"
                                                + " financial_reconciliation where"
                                                + " resource_type='REFUND' and transaction_id=?)",
                                        old))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void alreadyDispatchedNewRefundIsQueryOnlyAndBothSuccessesRemainVisibleOverBudget()
            throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.SUCCEEDED);
        long old = refund(approve(a, b, id));
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED))
                .when(provider)
                .refund(any());
        workflow.syncRefund(old);
        String newerCase = approve(a, b, id);
        long newer = refund(newerCase);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.PROCESSING))
                .when(provider)
                .refund(any());
        workflow.syncRefund(newer);
        var oldEvent = event(transactions.refund(old), "REFUND");
        inbox.process(inbox.record(oldEvent), oldEvent);
        assertThat(current(id, b).path("financialHold").asBoolean()).isTrue();
        assertThat(current(id, b).path("reconciliation").path("possibleInFlightRefundFen").asLong())
                .isEqualTo(2600);
        clearInvocations(provider);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.SUCCEEDED))
                .when(provider)
                .queryRefund(any());
        workflow.syncRefund(newer);
        workflow.handle("REFUND_RECONCILE", Long.toString(old), "{}");
        workflow.handle("REFUND_RECONCILE", Long.toString(newer), "{}");
        var repeated = event(transactions.refund(old), "REFUND");
        inbox.process(inbox.record(repeated), repeated);
        workflow.syncRefund(newer);
        verify(provider, never()).refund(any());
        var now = current(id, b);
        assertThat(now.path("reconciliation").path("confirmedRefundFen").asLong()).isEqualTo(5200);
        assertThat(now.path("refundedAmountFen").asLong()).isEqualTo(5200);
        assertThat(now.path("paidAmountFen").asLong()).isEqualTo(2600);
        assertThat(now.path("reconciliation").path("possibleInFlightRefundFen").asLong()).isZero();
        assertThat(now.path("reconciliation").path("overBudget").asBoolean()).isTrue();
        assertThat(now.path("reconciliation").path("issues")).hasSize(2);
        assertThat(now.path("refundedGoodsFen").asLong()).isZero();
        assertThat(now.path("financialHold").asBoolean()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select active_aftersale_id from store_order where id=?",
                                Long.class,
                                id))
                .isEqualTo(Long.parseLong(newerCase));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_ledger where order_item_id=(select"
                                        + " id from order_item where order_id=?) and"
                                        + " event_type='RETURN'",
                                Integer.class,
                                id))
                .isZero();
        assertThatCode(() -> workflow.handle("REFUND_SYNC", Long.toString(newer), "{}"))
                .as(
                        "Terminal reconciliation becomes an operational issue, not an endless"
                                + " ordinary refund retry")
                .doesNotThrowAnyException();
    }

    @Test
    void dispatchStartCrashRemainsQueryOnlyWhenHoldArrives() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.SUCCEEDED);
        long old = refund(approve(a, b, id));
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED))
                .when(provider)
                .refund(any());
        workflow.syncRefund(old);
        String newerCase = approve(a, b, id);
        long newer = refund(newerCase);
        assertThat(refundTransactions.prepare(transactions.refund(newer)))
                .isEqualTo(RefundTransactions.Action.SEND);
        // The process can die here before transport receives the request. Dispatch uncertainty is
        // durable.
        var conflict = event(transactions.refund(old), "REFUND");
        inbox.process(inbox.record(conflict), conflict);
        clearInvocations(provider);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.UNKNOWN))
                .when(provider)
                .queryRefund(any());
        assertThatThrownBy(() -> workflow.syncRefund(newer))
                .isInstanceOf(TaskHandler.RetryLater.class);
        verify(provider, never()).refund(any());
        assertThat(current(id, b).path("reconciliation").path("possibleInFlightRefundFen").asLong())
                .isEqualTo(2600);
        assertThat(
                        jdbc.queryForObject(
                                "select active_aftersale_id from store_order where id=?",
                                Long.class,
                                id))
                .isEqualTo(Long.parseLong(newerCase));
    }

    @Test
    void failureReturningFromInFlightSendCannotClearCaseAfterConflictArrives() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.SUCCEEDED);
        long old = refund(approve(a, b, id));
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED))
                .when(provider)
                .refund(any());
        workflow.syncRefund(old);
        String newerCase = approve(a, b, id);
        long newer = refund(newerCase);
        doAnswer(
                        x -> {
                            var conflict = event(transactions.refund(old), "REFUND");
                            inbox.process(inbox.record(conflict), conflict);
                            return result(x.getArgument(0), PaymentProvider.State.FAILED);
                        })
                .when(provider)
                .refund(any());
        workflow.syncRefund(newer);
        assertThat(
                        jdbc.queryForObject(
                                "select active_aftersale_id from store_order where id=?",
                                Long.class,
                                id))
                .isEqualTo(Long.parseLong(newerCase));
        assertThat(current(id, b).path("financialHold").asBoolean()).isTrue();
        assertThat(current(id, b).path("refundedGoodsFen").asLong()).isZero();
    }

    @Test
    void holdStillBlocksShipmentReceiptAndConsumerAfterNewRequestedCaseIsRejected()
            throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.SUCCEEDED);
        var o = current(id, b);
        write(
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-PRE-HOLD",
                        "reason",
                        "Ship"));
        String oldCase =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Compensation",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        1000,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + oldCase + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        1000,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "Compensation"));
        long old = refund(oldCase);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED))
                .when(provider)
                .refund(any());
        workflow.syncRefund(old);
        String newer =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Second request",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        1000,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        var conflict = event(transactions.refund(old), "REFUND");
        inbox.process(inbox.record(conflict), conflict);
        write(
                "/admin/aftersales/" + newer + "/reject",
                a,
                Map.of("reason", "Withdraw new request"));
        assertThat(
                        jdbc.queryForObject(
                                "select active_aftersale_id from store_order where id=?",
                                Long.class,
                                id))
                .isNull();
        var receipt =
                write(
                        "POST",
                        "/orders/" + id + "/confirm-receipt",
                        b,
                        Map.of(),
                        UUID.randomUUID().toString(),
                        409);
        assertThat(receipt.path("code").asText()).isEqualTo("FINANCIAL_RECONCILIATION_REQUIRED");
        var held = current(id, b);
        var shipment =
                write(
                        "POST",
                        "/admin/orders/" + id + "/ship",
                        a,
                        Map.of(
                                "version",
                                held.path("version").asLong(),
                                "carrierCode",
                                "DEV",
                                "trackingNo",
                                "DEV-HELD",
                                "reason",
                                "Blocked edit"),
                        UUID.randomUUID().toString(),
                        409);
        assertThat(shipment.path("code").asText()).isEqualTo("FINANCIAL_RECONCILIATION_REQUIRED");
        assertThat(held.path("allowedActions").toString())
                .doesNotContain("CONFIRM_RECEIPT", "APPLY_REFUND");
        var effects = new AtomicInteger();
        var consumer =
                new com.wechat.jxsc.order.CommerceEventConsumer() {
                    public String name() {
                        return "COMMISSIONS";
                    }

                    public boolean supports(String type) {
                        return true;
                    }

                    public void consume(String type, String key, JsonNode payload) {
                        effects.incrementAndGet();
                    }
                };
        assertThatThrownBy(
                        () -> delivery.deliver(consumer, "ORDER_PAID", id, json.createObjectNode()))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(effects.get()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commerce_event_receipt where"
                                        + " event_type='ORDER_PAID' and business_key=?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void directLateSignedSuccessWaitsForQueryUnknownThenRecoversOnce() throws Exception {
        latePayment(false);
    }

    @Test
    void directLateSignedSuccessWithNoStockCreatesOneRefundOnlyAfterConfirmedQuery()
            throws Exception {
        latePayment(true);
    }

    private void latePayment(boolean useStock) throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String id = start(a, b, 1, PaymentProvider.State.PROCESSING);
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.CLOSED))
                .when(provider)
                .query(any());
        write("/orders/" + id + "/cancel", b, Map.of());
        String product = current(id, b).path("item").path("productId").asText();
        if (useStock) {
            String other = buyer("buyer-active-member");
            order(other, quote(other, product, address(other).path("id").asText(), 1));
        }
        long payment =
                jdbc.queryForObject(
                        "select id from payment_transaction where order_id=?", Long.class, id);
        var intent = transactions.payment(payment);
        var callback = event(intent, "PAYMENT");
        long row = inbox.record(callback);
        inbox.process(row, callback);
        assertThat(current(id, b).path("orderStatus").asText()).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=?",
                                Integer.class,
                                id))
                .isZero();
        doAnswer(
                        x -> {
                            assertThat(x.getArgument(0, PaymentProvider.Intent.class).businessNo())
                                    .isEqualTo(intent.businessNo());
                            return result(x.getArgument(0), PaymentProvider.State.UNKNOWN);
                        })
                .when(provider)
                .query(any());
        assertThatThrownBy(() -> workflow.handle("PAYMENT_RECONCILE", Long.toString(row), "{}"))
                .isInstanceOf(TaskHandler.RetryLater.class);
        workflow.handle("INBOX_PROCESS", Long.toString(row), "{}");
        assertThat(current(id, b).path("orderStatus").asText()).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_task where task_type='ORDER_PAID' and"
                                        + " business_key=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=?",
                                Integer.class,
                                id))
                .isZero();
        doAnswer(x -> result(x.getArgument(0), PaymentProvider.State.SUCCEEDED))
                .when(provider)
                .query(any());
        workflow.handle("PAYMENT_RECONCILE", Long.toString(row), "{}");
        workflow.handle("PAYMENT_RECONCILE", Long.toString(row), "{}");
        var repeated = event(intent, "PAYMENT");
        inbox.process(inbox.record(repeated), repeated);
        assertThat(current(id, b).path("orderStatus").asText())
                .isEqualTo(useStock ? "CANCELLED" : "PAID");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_transaction where order_id=?",
                                Integer.class,
                                id))
                .isEqualTo(useStock ? 1 : 0);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_ledger where order_item_id=(select"
                                    + " id from order_item where order_id=?) and event_type='PAY'",
                                Integer.class,
                                id))
                .isEqualTo(useStock ? 0 : 1);
    }
}
