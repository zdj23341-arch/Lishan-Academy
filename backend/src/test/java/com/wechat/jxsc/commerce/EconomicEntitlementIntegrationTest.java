package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.wechat.jxsc.payment.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.*;

class EconomicEntitlementIntegrationTest extends DistributionTestSupport {
    @MockitoBean PaymentProvider provider;
    @Autowired PaymentWorkflow workflow;
    @Autowired PaymentTransactions transactions;
    @Autowired PaymentInbox inbox;

    PaymentProvider.Result result(PaymentProvider.Intent i, PaymentProvider.State state) {
        return new PaymentProvider.Result(
                state,
                "CORRECT-" + i.businessNo(),
                i.amountFen(),
                i.currency(),
                i.appId(),
                i.merchantId(),
                Map.of());
    }

    @Test
    void correctionRevokesMembershipAndReversesAttributedCommissionWithPermanentReceipts()
            throws Exception {
        when(provider.channel()).thenReturn("DEV");
        when(provider.prepay(any()))
                .thenAnswer(x -> result(x.getArgument(0), PaymentProvider.State.SUCCEEDED));
        String promoter = freshPromoter(), product = member(6);
        rule(product, did(promoter));
        String touch = touch(promoter, product);
        var quote = write("/orders/preview", b, Map.of("productId", product, "quantity", 1));
        String order =
                write(
                                "/orders",
                                b,
                                Map.of(
                                        "quoteId",
                                        quote.path("quoteId").asText(),
                                        "attributionToken",
                                        touch))
                        .path("id")
                        .asText();
        write("/payments/wechat/prepay", b, Map.of("orderId", order));
        worker.run(100);
        assertThat(me().path("state").asText()).isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "select earned_fen from commission_ledger where order_id=?",
                                Long.class,
                                order))
                .isEqualTo(12);
        String aftersale =
                write(
                                "/orders/" + order + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Membership cancellation",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        100,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "/admin/aftersales/" + aftersale + "/approve",
                a,
                Map.of(
                        "goodsRefundFen",
                        100,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "Approved"));
        long refund =
                jdbc.queryForObject(
                        "select id from refund_transaction where aftersale_id=?",
                        Long.class,
                        aftersale);
        when(provider.refund(any()))
                .thenAnswer(x -> result(x.getArgument(0), PaymentProvider.State.FAILED));
        workflow.syncRefund(refund);
        var i = transactions.refund(refund);
        var event =
                new PaymentProvider.VerifiedEvent(
                        i.channel(),
                        UUID.randomUUID().toString(),
                        "REFUND",
                        i.id(),
                        i.orderId(),
                        i.businessNo(),
                        "CORRECT-" + i.businessNo(),
                        i.amountFen(),
                        i.currency(),
                        i.appId(),
                        i.merchantId(),
                        PaymentProvider.State.SUCCEEDED,
                        true,
                        "Authenticated test boundary");
        inbox.process(inbox.record(event), event);
        when(provider.queryRefund(any()))
                .thenAnswer(x -> result(x.getArgument(0), PaymentProvider.State.SUCCEEDED));
        workflow.handle("REFUND_RECONCILE", "" + refund, "{}");
        write(
                "POST",
                "/admin/orders/" + order + "/reconcile",
                b,
                Map.of("reason", "Forbidden"),
                UUID.randomUUID().toString(),
                403);
        write(
                "/admin/orders/" + order + "/reconcile",
                a,
                Map.of("reason", "Authenticated evidence resolution"));
        worker.run(100);
        write(
                "/admin/orders/" + order + "/reconcile",
                a,
                Map.of("reason", "Replay evidence resolution"));
        worker.run(100);
        assertThat(me().path("canDownload").asBoolean()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select reversed_fen from commission_ledger where order_id=?",
                                Long.class,
                                order))
                .isEqualTo(12);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commerce_event_receipt where"
                                        + " event_type='REFUND_SUCCEEDED' and business_key=?",
                                Integer.class,
                                "correction:" + refund))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update economic_resolution set reason='rewrite' where"
                                                + " order_id=?",
                                        order))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var history = call("GET", "/admin/orders/" + order + "/economic-resolutions", a, null, 200);
        assertThat(history.path("items").get(0).path("id").isTextual()).isTrue();
    }
}
