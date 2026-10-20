package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.auth.WechatLoginService;
import com.wechat.jxsc.auth.wechat.WechatLoginClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

@org.springframework.test.context.TestPropertySource(properties = "jxsc.privacy.retention-days=365")
@org.springframework.context.annotation.Import(FinanceSqlGate.class)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AccountClosureIntegrationTest extends EntitlementTestSupport {
    @Autowired WechatLoginService login;
    @Autowired com.wechat.jxsc.common.commerce.ProtectedOperationResults results;
    @Autowired com.wechat.jxsc.common.commerce.SensitiveCipher cipher;
    @Autowired com.wechat.jxsc.membership.EntitlementLedger ledger;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.wechat.jxsc.fulfillment.ShipmentService shipments;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;

    @Test
    void retainedLegacyPayloadReturnsExactStringIdentifiersAndPreservesNumbers() throws Exception {
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        var large = new java.math.BigInteger("9007199254740993");
        var maximum = new java.math.BigInteger("18446744073709551615");
        var payload = json.createObjectNode();
        payload.putArray("addresses")
                .add(
                        json.createObjectNode()
                                .put("id", large)
                                .put("order_id", maximum)
                                .put("version", 7)
                                .put("name_cipher", cipher.encrypt("Synthetic recipient"))
                                .put("phone_cipher", cipher.encrypt("13800000000"))
                                .put("detail_cipher", cipher.encrypt("Synthetic address")));
        payload.putArray("aftersales")
                .add(
                        json.createObjectNode()
                                .put("id", maximum)
                                .put("order_id", large)
                                .put("requested_goods_fen", 12345));
        payload.putArray("shipments")
                .add(
                        json.createObjectNode()
                                .put("id", large)
                                .put("order_id", maximum)
                                .put("shipped_quantity", 2));
        payload.putArray("receipt")
                .add(json.createObjectNode().put("recipient_identity_id", maximum));
        payload.putArray("identities")
                .add(
                        json.createObjectNode()
                                .put("app_id", "wxSynthetic")
                                .put("openid", "opaque-identity"));
        jdbc.update(
                "insert into retained_personal_data(user_id,payload_cipher,retain_until)"
                    + " values(?,?,?)",
                uid,
                cipher.encrypt(json.writeValueAsString(payload)),
                java.sql.Timestamp.from(clock.instant().plusSeconds(86400)));
        var data =
                call(
                                "POST",
                                "/admin/retained-personal-data/" + uid + "/read",
                                a,
                                Map.of("reason", "Synthetic legacy ID contract"),
                                200)
                        .path("data");
        for (String pointer :
                List.of("/addresses/0/id", "/aftersales/0/order_id", "/shipments/0/id")) {
            assertThat(data.at(pointer).isTextual()).as(pointer).isTrue();
            assertThat(data.at(pointer).asText()).isEqualTo(large.toString());
        }
        for (String pointer :
                List.of(
                        "/addresses/0/order_id",
                        "/aftersales/0/id",
                        "/shipments/0/order_id",
                        "/receipt/0/recipient_identity_id")) {
            assertThat(data.at(pointer).isTextual()).as(pointer).isTrue();
            assertThat(data.at(pointer).asText()).isEqualTo(maximum.toString());
        }
        assertThat(data.at("/addresses/0/version").isIntegralNumber()).isTrue();
        assertThat(data.at("/aftersales/0/requested_goods_fen").isIntegralNumber()).isTrue();
        assertThat(data.at("/aftersales/0/requested_goods_fen").asInt()).isEqualTo(12345);
        assertThat(data.at("/shipments/0/shipped_quantity").asInt()).isEqualTo(2);
        assertThat(data.at("/identities/0/app_id").asText()).isEqualTo("wxSynthetic");
        assertThat(
                        cipher.decrypt(
                                jdbc.queryForObject(
                                        "select payload_cipher from retained_personal_data where"
                                            + " user_id=?",
                                        String.class,
                                        uid)))
                .isEqualTo(json.writeValueAsString(payload));
    }

    @Test
    void succeededPaymentRequiresConfiguredRetentionEvenWithoutOrderPaidTimestamp()
            throws Exception {
        var order = paidOrder(a, b, 1);
        String id = order.path("id").asText();
        jdbc.update(
                "update store_order set"
                    + " paid_at=null,order_status='COMPLETED',fulfillment_status='RECEIVED' where"
                    + " id=?",
                id);
        environment
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "closure-policy-test", Map.of("jxsc.privacy.retention-days", 0)));
        try {
            var response =
                    write(
                            "POST",
                            "/me/close",
                            b,
                            Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                            UUID.randomUUID().toString(),
                            409);
            assertThat(response.path("code").asText())
                    .isEqualTo("PRIVACY_RETENTION_NOT_CONFIGURED");
            call("GET", "/me", b, null, 200);
        } finally {
            environment.getPropertySources().remove("closure-policy-test");
        }
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        var retained =
                call(
                        "POST",
                        "/admin/retained-personal-data/" + uid + "/read",
                        a,
                        Map.of("reason", "Synthetic recovered payment"),
                        200);
        assertThat(retained.path("data").path("addresses").get(0).path("phone").asText())
                .isEqualTo("13800000000");
    }

    @org.junit.jupiter.api.AfterEach
    void resetGate() {
        FinanceSqlGate.observer = (sql, after) -> {};
    }

    @Test
    void staleAuthenticatedMutationAndOldIdentityReadCannotCrossClosure() throws Exception {
        var identity = new WechatLoginClient.Identity("wxRace", UUID.randomUUID().toString(), null);
        var session = login.provision(identity, "device");
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (Thread.currentThread().getName().equals("old-login")
                            && !after
                            && sql.contains("select state from account_lifecycle")) {
                        read.countDown();
                        FinanceSqlGate.await(release);
                    }
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var future =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("old-login");
                                return login.provision(identity, "waiting");
                            });
            FinanceSqlGate.await(read);
            write(
                    "/me/close",
                    session.accessToken(),
                    Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
            release.countDown();
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.wechat.jxsc.common.ApiException.class);
        } finally {
            release.countDown();
            FinanceSqlGate.observer = (sql, after) -> {};
        }
        var active = login.provision(identity, "new");
        var admitted = new CountDownLatch(1);
        var mutationRelease = new CountDownLatch(1);
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (Thread.currentThread().getName().equals("stale-address")
                            && !after
                            && sql.contains("select state from account_lifecycle")) {
                        admitted.countDown();
                        FinanceSqlGate.await(mutationRelease);
                    }
                };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var mutation =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("stale-address");
                                return call(
                                        "POST",
                                        "/addresses",
                                        active.accessToken(),
                                        addressBody(true),
                                        401);
                            });
            FinanceSqlGate.await(admitted);
            write(
                    "/me/close",
                    active.accessToken(),
                    Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
            mutationRelease.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from user_address where user_id=?",
                                    Long.class,
                                    active.user().id()))
                    .isZero();
        } finally {
            mutationRelease.countDown();
        }
    }

    @Test
    void balancesBlockClosureButPaidOutHistoryDoesNot() throws Exception {
        var distributor =
                call(
                        "POST",
                        "/admin/distributors",
                        a,
                        Map.of(
                                "userId",
                                Long.toString(uid),
                                "reason",
                                "Synthetic closure balance check"),
                        200);
        for (String balance :
                List.of("pending_fen", "available_fen", "withdrawing_fen", "debt_fen")) {
            jdbc.update(
                    "update commission_account set " + balance + "=1 where distributor_id=?",
                    distributor.path("id").asText());
            write(
                    "POST",
                    "/me/close",
                    b,
                    Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                    UUID.randomUUID().toString(),
                    409);
            call("GET", "/me", b, null, 200);
            jdbc.update(
                    "update commission_account set " + balance + "=0 where distributor_id=?",
                    distributor.path("id").asText());
        }
        jdbc.update(
                "update commission_account set paid_out_fen=100 where distributor_id=?",
                distributor.path("id").asText());
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        assertThat(
                        jdbc.queryForObject(
                                "select paid_out_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                distributor.path("id").asText()))
                .isEqualTo(100);
    }

    @Test
    void ordinaryAndCachedOrderViewsRedactRetainedAddressWithSeparateAuditedAccess()
            throws Exception {
        var order = paidOrder(a, b, 1);
        String id = order.path("id").asText();
        var stored = results.protect(order);
        write(
                "/admin/orders/" + id + "/ship",
                a,
                Map.of(
                        "version",
                        order.path("version").asLong(),
                        "carrierCode",
                        "DEV",
                        "trackingNo",
                        "PRIVATE-TRACK",
                        "reason",
                        "Synthetic shipping"));
        jdbc.update(
                "update shipment set next_sync_at=? where order_id=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
                id);
        var pending = shipments.prepare(Long.parseLong(id));
        jdbc.update(
                "update shipment set events_json=? where order_id=?",
                json.writeValueAsString(
                        List.of(
                                Map.of(
                                        "occurredAt",
                                        clock.instant().toString(),
                                        "description",
                                        "private recipient 13800000000"))),
                id);
        // Synthetic completion with a previously prepared provider response still in flight.
        jdbc.update(
                "update store_order set"
                    + " order_status='COMPLETED',fulfillment_status='RECEIVED',completed_at=UTC_TIMESTAMP(6)"
                    + " where id=?",
                id);
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        assertThat(call("GET", "/admin/orders/" + id, a, null, 200).path("address").isNull())
                .isTrue();
        assertThat(results.reveal(stored).path("address").isNull()).isTrue();
        shipments.synced(
                pending,
                new com.wechat.jxsc.fulfillment.LogisticsProvider.Tracking(
                        "DELIVERED",
                        false,
                        List.of(
                                new com.wechat.jxsc.fulfillment.FulfillmentDtos.Event(
                                        clock.instant(), "late private recipient"))));
        assertThat(shipments.prepare(Long.parseLong(id))).isNull();
        var logistics = shipments.get(Long.parseLong(id), true);
        assertThat(logistics.trackingNo()).isNull();
        assertThat(logistics.events()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select events_json from shipment where order_id=?",
                                String.class,
                                id))
                .isEqualTo("[]");
        String path = "/admin/retained-personal-data/" + uid + "/read";
        call(
                "POST",
                path,
                admin("ORDER_OPERATOR"),
                Map.of("reason", "Review retained purchase"),
                403);
        call("POST", path, a, Map.of("reason", ""), 400);
        var retained =
                call("POST", path, a, Map.of("reason", "Synthetic finance retention review"), 200);
        assertThat(retained.path("data").path("addresses").get(0).path("phone").asText())
                .isEqualTo("13800000000");
        assertThat(retained.path("data").path("shipments").get(0).path("tracking_no").asText())
                .isEqualTo("PRIVATE-TRACK");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from operation_audit_log where"
                                        + " action='FINANCIAL_RETENTION_READ' and resource_id=?",
                                Long.class,
                                Long.toString(uid)))
                .isEqualTo(1);
    }

    @Test
    void closesCurrentIdentityAndSessionsWithoutRevivingHistoryOnNewLogin() throws Exception {
        var identity =
                new WechatLoginClient.Identity(
                        "wxClose", UUID.randomUUID().toString(), "union-private");
        var first = login.provision(identity, "device1");
        var second = login.provision(identity, "device2");
        long user = Long.parseLong(first.user().id());
        var address = address(first.accessToken());
        write(
                "/me/close",
                first.accessToken(),
                Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        call("GET", "/me", first.accessToken(), null, 401);
        call("GET", "/me", second.accessToken(), null, 401);
        assertThat(
                        jdbc.queryForObject(
                                "select status from user_account where id=?", String.class, user))
                .isEqualTo("ANONYMIZED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_wechat_identity where user_id=? and"
                                        + " (openid=? or unionid is not null)",
                                Long.class,
                                user,
                                identity.openid()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from user_address where id=?",
                                Boolean.class,
                                address.path("id").asText()))
                .isTrue();
        var erased =
                jdbc.queryForMap(
                        "select * from user_address where id=?", address.path("id").asText());
        for (String field : List.of("name_cipher", "phone_cipher", "detail_cipher"))
            assertThat(cipher.decrypt((String) erased.get(field))).isEqualTo("ANONYMIZED");
        for (String field : List.of("province_code", "city_code", "district_code"))
            assertThat(erased.get(field)).isEqualTo("000000");
        var profile =
                jdbc.queryForMap(
                        "select nickname,avatar_url,phone_cipher from user_account where id=?",
                        user);
        assertThat(profile.get("nickname")).isEqualTo("ANONYMIZED");
        assertThat(profile.get("avatar_url")).isNull();
        assertThat(profile.get("phone_cipher")).isNull();
        var fresh = login.provision(identity, "device3");
        assertThat(fresh.user().id()).isNotEqualTo(first.user().id());
        assertThat(call("GET", "/addresses", fresh.accessToken(), null, 200).path("total").asLong())
                .isZero();
        for (String table : List.of("store_order", "entitlement", "distributor"))
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from " + table + " where user_id=?",
                                    Long.class,
                                    fresh.user().id()))
                    .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from account_closure where user_id=?",
                                Long.class,
                                user))
                .isEqualTo(1);
    }

    @Test
    void pendingOrderDefersClosureWithoutRevokingSessionAndPermanentRightsCanBeForfeited()
            throws Exception {
        var c = course("PERMANENT", null);
        var video =
                upload(
                        a,
                        "COURSE_VIDEO",
                        "DEV.mp4",
                        "video/mp4",
                        HexFormat.of()
                                .parseHex(
                                        "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000"));
        var chapter =
                call(
                        "POST",
                        "/admin/courses/" + c.path("id").asText() + "/chapters",
                        a,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV closed chapter",
                                "sortNo",
                                0,
                                "durationSeconds",
                                120,
                                "isTrial",
                                false,
                                "enabled",
                                true,
                                "videoAssetId",
                                video.path("id").asText()),
                        200);
        var q =
                write(
                        "/orders/preview",
                        b,
                        Map.of("productId", c.path("productId").asText(), "quantity", 1));
        var o = order(b, q);
        write(
                "POST",
                "/me/close",
                b,
                Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true),
                UUID.randomUUID().toString(),
                409);
        call("GET", "/me", b, null, 200);
        write("/dev/orders/" + o.path("id").asText() + "/pay", b, Map.of("scenario", "SUCCESS"));
        deliver(o.path("id").asText());
        var ticket =
                call(
                        "GET",
                        "/courses/"
                                + c.path("id").asText()
                                + "/chapters/"
                                + chapter.path("id").asText()
                                + "/play-url",
                        b,
                        null,
                        200);
        write(
                "POST",
                "/me/close",
                b,
                Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", false),
                UUID.randomUUID().toString(),
                409);
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        assertThat(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get(
                                                ticket.path("url").asText()))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        deliver(o.path("id").asText());
        refund(o.path("id").asText(), true, "closed-refund-" + o.path("id").asText());
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx ->
                                ledger.grant(
                                        uid,
                                        null,
                                        "late-replay:" + uid,
                                        Long.parseLong(c.path("id").asText()),
                                        clock.instant(),
                                        null,
                                        null));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement e where e.user_id=? and not"
                                        + " exists(select 1 from entitlement_event v where"
                                        + " v.source_key=e.source_key and v.event_type='REVOKE')",
                                Long.class,
                                uid))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement where user_id=?",
                                Long.class,
                                uid))
                .isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from store_order where user_id=?",
                                Long.class,
                                uid))
                .isEqualTo(1);
        assertThat(
                        call("GET", "/admin/orders/" + o.path("id").asText(), a, null, 200)
                                .path("orderStatus")
                                .asText())
                .isEqualTo("COMPLETED");
    }
}
