package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.*;

class DistributionIntegrationTest extends DistributionTestSupport {
    @Test
    void optionalSummaryIsEmptyForOrdinaryCustomerAndPreservesDisabledArchive() throws Exception {
        String ordinary = ordinaryCustomer();
        String userId = call("GET", "/me", ordinary, null, 200).path("id").asText();
        assertThat(call("GET", "/distribution/summary?optional=true", ordinary, null, 200).isNull())
                .isTrue();
        assertThat(call("GET", "/distribution/summary", ordinary, null, 404).path("code").asText())
                .isEqualTo("DISTRIBUTOR_NOT_ENABLED");
        assertThat(jdbc.queryForObject("select count(*) from distributor where user_id=?", Long.class, userId))
                .isZero();
        call("GET", "/distribution/summary?optional=true", null, null, 401);
        call("GET", "/distribution/summary?optional=true", a, null, 403);

        String promoter = freshPromoter();
        String distributorId = did(promoter);
        jdbc.update("update distributor set status='DISABLED' where id=?", distributorId);
        var disabled = call("GET", "/distribution/summary?optional=true", promoter, null, 200);
        assertThat(disabled.path("distributorId").asText()).isEqualTo(distributorId);
        assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.path("withdrawalEnabled").asBoolean()).isFalse();
    }

    @Test
    void ordinaryCustomerHasExplicitQualificationErrorAndCannotBind() throws Exception {
        String token = ordinaryCustomer();
        String userId = call("GET", "/me", token, null, 200).path("id").asText();
        for (String path : List.of("/distribution/summary", "/distribution/commissions", "/distribution/withdrawals")) {
            assertThat(call("GET", path, token, null, 404).path("code").asText())
                    .isEqualTo("DISTRIBUTOR_NOT_ENABLED");
        }
        assertThat(call("POST", "/distribution/receipt-profile", token, Map.of(), 404).path("code").asText())
                .isEqualTo("DISTRIBUTOR_NOT_ENABLED");
        assertThat(jdbc.queryForObject("select count(*) from distributor where user_id=?", Long.class, userId)).isZero();
    }
    @Test
    void summarySeparatesReceiptReadinessFromChannelAndQualification() throws Exception {
        String token = freshPromoter();
        String distributorId = did(token);
        String product = book(a, 10, 9990);
        rule(product, distributorId);
        var ready = call("GET", "/distribution/summary", token, null, 200);
        assertThat(ready.path("receiptReady").asBoolean()).isTrue();
        assertThat(ready.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(ready.path("promotableProductCount").asLong()).isEqualTo(1);
        jdbc.update("update distributor set receipt_ready=false where id=?", distributorId);
        var unbound = call("GET", "/distribution/summary", token, null, 200);
        assertThat(unbound.path("receiptReady").asBoolean()).isFalse();
        assertThat(unbound.path("disabledReason").asText()).isEqualTo("WITHDRAWAL_NOT_READY");
        jdbc.update("update distributor set receipt_ready=true,status='DISABLED' where id=?", distributorId);
        var disabled = call("GET", "/distribution/summary", token, null, 200);
        assertThat(disabled.path("receiptReady").asBoolean()).isTrue();
        assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.path("withdrawalEnabled").asBoolean()).isFalse();
        assertThat(disabled.path("disabledReason").asText()).isEqualTo("DISTRIBUTOR_DISABLED");
    }
    @Test
    void latestSameProductTouchCreditsOneFrozenSourceAndLiteralRefund() throws Exception {
        String pa = freshPromoter(), pb = freshPromoter();
        String p = book(a, 10, 9990);
        rule(p, did(pa), did(pb));
        touch(pa, p);
        clock.now = clock.now.plusSeconds(1);
        String chosen = touch(pb, p);
        var q = quote(b, p, address(b).path("id").asText(), 1);
        String id =
                write(
                                "/orders",
                                b,
                                Map.of(
                                        "quoteId",
                                        q.path("quoteId").asText(),
                                        "attributionToken",
                                        chosen))
                        .path("id")
                        .asText();
        jdbc.update(
                "update referral_touch set expires_at=? where token_hash=?",
                Timestamp.from(clock.now.plusSeconds(1)),
                com.wechat.jxsc.auth.TokenCodec.hash(chosen));
        clock.now = clock.now.plusSeconds(2);
        write("/dev/orders/" + id + "/pay", b, Map.of("scenario", "SUCCESS"));
        events.handle("ORDER_PAID", id, "{}");
        events.handle("ORDER_PAID", id, "{}");
        assertThat(
                        jdbc.queryForObject(
                                "select earned_fen from commission_ledger where order_id=?",
                                Long.class,
                                id))
                .isEqualTo(1248);
        assertThat(
                        jdbc.queryForObject(
                                "select distributor_id from commission_ledger where order_id=?",
                                String.class,
                                id))
                .isEqualTo(did(pb));
        jdbc.update("update store_order set refunded_goods_fen=3330 where id=?", id);
        events.handle(
                "REFUND_SUCCEEDED",
                "literal-refund",
                json.writeValueAsString(Map.of("orderId", id)));
        events.handle(
                "REFUND_SUCCEEDED",
                "literal-refund",
                json.writeValueAsString(Map.of("orderId", id)));
        assertThat(
                        jdbc.queryForObject(
                                "select reversed_fen from commission_ledger where order_id=?",
                                Long.class,
                                id))
                .isEqualTo(416);
        assertThat(
                        jdbc.queryForObject(
                                "select pending_fen from commission_account where distributor_id=?",
                                Long.class,
                                did(pb)))
                .isEqualTo(832);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "delete from commission_account_ledger where order_id=?",
                                        id))
                .isInstanceOf(Exception.class);
    }

    @Test
    void qualificationAndRoutesAreRealAndRoleScoped() throws Exception {
        String pa = promoter("distributor-a");
        assertThat(call("GET", "/me", pa, null, 200).path("distributor").asBoolean()).isTrue();
        assertThat(
                        call(
                                        "POST",
                                        "/auth/dev-login",
                                        null,
                                        Map.of("fixture", "distributor-a"),
                                        200)
                                .path("user")
                                .path("distributor")
                                .asBoolean())
                .isTrue();
        String analyst = admin("ANALYST");
        call("GET", "/admin/distributors", analyst, null, 403);
        call(
                "GET",
                "/admin/analytics/overview?startDate=2026-10-06&endDate=2026-10-07",
                analyst,
                null,
                200);
        call(
                "PATCH",
                "/admin/settings",
                a,
                Map.of(
                        "version",
                        0,
                        "values",
                        Map.of("merchantSecret", "bad"),
                        "reason",
                        "No secrets"),
                400);
        write(
                "POST",
                "/distribution/withdrawals",
                pa,
                Map.of("amountFen", 100, "openid", "other"),
                UUID.randomUUID().toString(),
                400);
    }
}
