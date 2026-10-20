package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

@TestPropertySource(properties = "jxsc.privacy.retention-days=365")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class FinalContractClosureIntegrationTest extends DistributionTestSupport {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void contentActionRequiresOwnedSurvivingDigitalRight(boolean membership) throws Exception {
        String product = membership ? member(6) : course("DAYS", 1).path("productId").asText();
        String id = paid(product, clock.instant().toString());
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("allowedActions").toString())
                .doesNotContain("VIEW_CONTENT");
        deliver(id);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("allowedActions").toString())
                .contains("VIEW_CONTENT");
        call("GET", "/orders/" + id, buyer("buyer-active-member"), null, 404);
        clock.now = clock.now.plusSeconds(400 * 86400L);
        b = buyer("buyer");
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("allowedActions").toString())
                .doesNotContain("VIEW_CONTENT");
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.wechat.jxsc.distribution.CommissionService commissions;

    @Test
    void orderContractShowsConfirmedMoneyAndApplicableActions() throws Exception {
        var o = paidOrder(a, b, 1);
        assertThat(o.path("paidAmountFen").asLong()).isEqualTo(2600);
        assertThat(o.has("refundedAmountFen")).isTrue();
        assertThat(o.path("item").has("coverUrl")).isTrue();
        assertThat(o.path("allowedActions").toString())
                .contains("APPLY_AFTERSALE")
                .doesNotContain("APPLY_REFUND", "VIEW_CONTENT", "VIEW_LOGISTICS");
        write(
                "/admin/orders/" + o.path("id").asText() + "/ship",
                a,
                Map.of(
                        "version",
                        o.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "DEV-CONTRACT",
                        "reason",
                        "Contract shipping"));
        assertThat(
                        call("GET", "/orders/" + o.path("id").asText(), b, null, 200)
                                .path("allowedActions")
                                .toString())
                .contains("VIEW_LOGISTICS");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fullyRefundedPhysicalOrderCanCloseWithoutChangingHistory(boolean shipped)
            throws Exception {
        var o = paidOrder(a, b, 1);
        String id = o.path("id").asText();
        if (shipped)
            write(
                    "/admin/orders/" + id + "/ship",
                    a,
                    Map.of(
                            "version",
                            o.path("version").asLong(),
                            "carrierCode",
                            "DEV",
                            "trackingNo",
                            "DEV-FULL-RETURN",
                            "reason",
                            "Ship return fixture"));
        String sale =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        shipped ? "RETURN_REFUND" : "REFUND_ONLY",
                                        "reason",
                                        "Full terminal refund",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        2000,
                                        "requestedFreightFen",
                                        600))
                        .path("id")
                        .asText();
        var decision =
                new HashMap<String, Object>(
                        Map.of(
                                "goodsRefundFen",
                                2000,
                                "freightRefundFen",
                                600,
                                "returnedQuantity",
                                1,
                                "reason",
                                "Full refund"));
        if (shipped) decision.put("returnAddress", "Synthetic return warehouse");
        write("/admin/aftersales/" + sale + "/approve", a, decision);
        write(
                "POST",
                "/me/close",
                b,
                Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                UUID.randomUUID().toString(),
                409);
        if (shipped) {
            write(
                    "/aftersales/" + sale + "/return-shipment",
                    b,
                    Map.of("carrierCode", "DEV", "trackingNo", "DEV-RETURN"));
            write(
                    "POST",
                    "/me/close",
                    b,
                    Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                    UUID.randomUUID().toString(),
                    409);
            write(
                    "/admin/aftersales/" + sale + "/receive-return",
                    a,
                    Map.of("returnedQuantity", 1, "reason", "Return received"));
        }
        write("/dev/aftersales/" + sale + "/refund", b, Map.of("scenario", "SUCCESS"));
        var refunded = call("GET", "/orders/" + id, b, null, 200);
        assertThat(refunded.path("refundedAmountFen").asLong()).isEqualTo(2600);
        assertThat(refunded.path("allowedActions").toString())
                .doesNotContain("APPLY_AFTERSALE", "CONFIRM_RECEIPT");
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        assertThat(
                        jdbc.queryForObject(
                                "select order_status from store_order where id=?",
                                String.class,
                                id))
                .isEqualTo("PAID");
    }

    String source(String promoter) throws Exception {
        String product = book(a, 10, 2000);
        rule(product, did(promoter));
        String token = touch(promoter, product);
        var q = quote(b, product, address(b).path("id").asText(), 1);
        String id =
                write(
                                "/orders",
                                b,
                                Map.of(
                                        "quoteId",
                                        q.path("quoteId").asText(),
                                        "attributionToken",
                                        token))
                        .path("id")
                        .asText();
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        jdbc.update(
                "update store_order set"
                    + " order_status='COMPLETED',completed_at=UTC_TIMESTAMP(6),fulfillment_status='RECEIVED'"
                    + " where id=?",
                id);
        return id;
    }

    @Test
    void committedUnconsumedSourcePreventsDistributorClosure() throws Exception {
        String promoter = freshPromoter();
        source(promoter);
        var result =
                write(
                        "POST",
                        "/me/close",
                        promoter,
                        Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                        UUID.randomUUID().toString(),
                        409);
        assertThat(result.path("code").asText()).isEqualTo("ACCOUNT_OBLIGATIONS_PENDING");
    }

    @Test
    void paidOutSourceAftersaleBlocksButSettledHistoryAllowsClosure() throws Exception {
        String promoter = freshPromoter();
        String id = source(promoter);
        events.handle("ORDER_PAID", id, "{}");
        jdbc.update(
                "update commission_account set pending_fen=0,paid_out_fen=250 where"
                        + " distributor_id=?",
                did(promoter));
        String sale =
                write(
                                "/orders/" + id + "/refund",
                                b,
                                Map.of(
                                        "type",
                                        "REFUND_ONLY",
                                        "reason",
                                        "Source obligation",
                                        "evidenceAssetIds",
                                        List.of(),
                                        "requestedGoodsFen",
                                        100,
                                        "requestedFreightFen",
                                        0))
                        .path("id")
                        .asText();
        write(
                "POST",
                "/me/close",
                promoter,
                Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                UUID.randomUUID().toString(),
                409);
        write(
                "/admin/aftersales/" + sale + "/reject",
                a,
                Map.of("reason", "Buyer withdrew request"));
        events.handle("AFTERSALE_OPENED", sale, json.writeValueAsString(Map.of("orderId", id)));
        events.handle("AFTERSALE_CLOSED", sale, json.writeValueAsString(Map.of("orderId", id)));
        write(
                "/me/close",
                promoter,
                Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void partialAndFullReversalAreProjectedWithoutChangingMaturity(boolean matured)
            throws Exception {
        String promoter = freshPromoter();
        String id = source(promoter);
        events.handle("ORDER_PAID", id, "{}");
        if (matured) {
            clock.now = clock.now.plusSeconds(30 * 86400L);
            commissions.mature(Long.parseLong(id));
        }
        jdbc.update("update store_order set refunded_goods_fen=1000 where id=?", id);
        events.handle(
                "REFUND_SUCCEEDED",
                "final-partial-" + id,
                json.writeValueAsString(Map.of("orderId", id)));
        var rows = call("GET", "/distribution/commissions", promoter, null, 200).path("items");
        assertThat(rows.get(0).path("state").asText()).isEqualTo("PARTIALLY_REVERSED");
        assertThat(
                        jdbc.queryForObject(
                                "select state from commission_ledger where order_id=?",
                                String.class,
                                id))
                .isEqualTo(matured ? "AVAILABLE" : "PENDING");
        jdbc.update("update store_order set refunded_goods_fen=2000 where id=?", id);
        events.handle(
                "REFUND_SUCCEEDED",
                "final-full-" + id,
                json.writeValueAsString(Map.of("orderId", id)));
        events.handle(
                "REFUND_SUCCEEDED",
                "final-full-" + id,
                json.writeValueAsString(Map.of("orderId", id)));
        assertThat(
                        call("GET", "/distribution/commissions", promoter, null, 200)
                                .path("items")
                                .get(0)
                                .path("state")
                                .asText())
                .isEqualTo("REVERSED");
        assertThat(
                        jdbc.queryForObject(
                                "select pending_fen from commission_account where distributor_id=?",
                                Long.class,
                                did(promoter)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select available_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                did(promoter)))
                .isZero();
    }
}
