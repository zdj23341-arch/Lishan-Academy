package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.order.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class EntitlementReplayIntegrationTest extends EntitlementTestSupport {
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrderService orders;

    void awaitWait(long connection) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(
                            "select count(*) from performance_schema.data_lock_waits w join"
                                + " performance_schema.threads t on"
                                + " t.thread_id=w.requesting_thread_id where t.processlist_id=?",
                            Integer.class,
                            connection)
                    > 0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Expected an observed InnoDB lock wait");
    }

    @Test
    void concurrentRenewalAfterOldSnapshotUsesCurrentGrantsAndDoesNotLoseMonths() throws Exception {
        String first = paid(member(6), "2026-10-06T02:00:00Z"),
                second = paid(member(12), "2026-10-10T02:00:00Z");
        var ready = new CountDownLatch(1);
        var connection = new CompletableFuture<Long>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .executeWithoutResult(
                                                    s -> {
                                                        deliver(first);
                                                        ready.countDown();
                                                        try {
                                                            awaitWait(
                                                                    connection.get(
                                                                            10, TimeUnit.SECONDS));
                                                        } catch (Exception e) {
                                                            throw new AssertionError(e);
                                                        }
                                                    }));
            var two =
                    pool.submit(
                            () -> {
                                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                s -> {
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "select count(*) from"
                                                                                + " membership_grant"
                                                                                + " where"
                                                                                + " user_id=?",
                                                                            Integer.class,
                                                                            uid))
                                                            .isZero();
                                                    connection.complete(
                                                            jdbc.queryForObject(
                                                                    "select connection_id()",
                                                                    Long.class));
                                                    deliver(second);
                                                });
                                return null;
                            });
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(me().path("expiresAt").asText()).isEqualTo("2028-04-06T02:00:00Z");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from membership_grant where user_id=?",
                                Integer.class,
                                uid))
                .isEqualTo(2);
    }

    @Test
    void checkoutWaitingOnDeliveryRejectsActiveCourseDespiteEarlierRepeatableReadSnapshot()
            throws Exception {
        var c = course("DAYS", 30);
        String first = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        String q =
                write(
                                "/orders/preview",
                                b,
                                Map.of("productId", c.path("productId").asText(), "quantity", 1))
                        .path("quoteId")
                        .asText();
        var ready = new CountDownLatch(1);
        var connection = new CompletableFuture<Long>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var delivery =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .executeWithoutResult(
                                                    s -> {
                                                        deliver(first);
                                                        ready.countDown();
                                                        try {
                                                            awaitWait(
                                                                    connection.get(
                                                                            10, TimeUnit.SECONDS));
                                                        } catch (Exception e) {
                                                            throw new AssertionError(e);
                                                        }
                                                    }));
            var checkout =
                    pool.submit(
                            () -> {
                                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                                var actor =
                                        new CurrentActor(
                                                CurrentActor.Type.CUSTOMER,
                                                uid,
                                                null,
                                                Set.of(),
                                                false);
                                SecurityContextHolder.getContext()
                                        .setAuthentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        actor, null, List.of()));
                                try {
                                    assertThatThrownBy(
                                                    () ->
                                                            new TransactionTemplate(transactions)
                                                                    .execute(
                                                                            s -> {
                                                                                jdbc.queryForObject(
                                                                                        "select"
                                                                                            + " count(*)"
                                                                                            + " from"
                                                                                            + " entitlement"
                                                                                            + " where"
                                                                                            + " user_id=?",
                                                                                        Integer
                                                                                                .class,
                                                                                        uid);
                                                                                connection.complete(
                                                                                        jdbc
                                                                                                .queryForObject(
                                                                                                        "select"
                                                                                                            + " connection_id()",
                                                                                                        Long
                                                                                                                .class));
                                                                                return orders
                                                                                        .create(
                                                                                                new OrderDtos
                                                                                                        .Create(
                                                                                                        q,
                                                                                                        null),
                                                                                                UUID.randomUUID()
                                                                                                        .toString());
                                                                            }))
                                            .isInstanceOf(com.wechat.jxsc.common.ApiException.class)
                                            .hasMessageContaining("active course");
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                                return null;
                            });
            delivery.get(20, TimeUnit.SECONDS);
            checkout.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void delayedOutOfOrderDeliveryReplaysChronologicallyAndDoesNotFillRealGaps() throws Exception {
        String half = member(6), year = member(12);
        String first = paid(half, "2026-10-06T02:00:00Z"),
                second = paid(year, "2026-10-10T02:00:00Z");
        deliver(second);
        deliver(first);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2028-04-06T02:00:00Z");
        refund(first, true, "early-" + first);
        refund(second, true, "later-" + second);
        String past = paid(half, "2025-01-01T02:00:00Z"),
                future = paid(year, "2027-01-01T02:00:00Z");
        deliver(past);
        deliver(future);
        assertThat(me().path("status").asText()).isEqualTo("PENDING");
        assertThat(me().path("startsAt").asText()).isEqualTo("2027-01-01T02:00:00Z");
    }

    @Test
    void fullRefundPreviewUsesSameReplayAsActualSuccessAndDoesNotMutateUntilSuccess()
            throws Exception {
        String first = paid(member(6), "2026-10-06T02:00:00Z"),
                second = paid(member(12), "2026-10-10T02:00:00Z");
        deliver(first);
        deliver(second);
        var refund =
                write(
                        "/orders/" + first + "/refund",
                        b,
                        Map.of(
                                "type",
                                "REFUND_ONLY",
                                "reason",
                                "DEV refund",
                                "evidenceAssetIds",
                                List.of(),
                                "requestedGoodsFen",
                                100,
                                "requestedFreightFen",
                                0));
        String id = refund.path("id").asText();
        var decision =
                Map.of(
                        "goodsRefundFen",
                        100,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "DEV approve");
        var before =
                jdbc.queryForObject(
                        "select count(*) from entitlement_event where user_id=?",
                        Integer.class,
                        uid);
        var preview =
                call("POST", "/admin/aftersales/" + id + "/preview-decision", a, decision, 200);
        assertThat(preview.at("/consumerPreview/entitlement/expiresAt").asText())
                .isEqualTo("2027-10-10T02:00:00Z");
        assertThat(me().path("expiresAt").asText()).isEqualTo("2028-04-06T02:00:00Z");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement_event where user_id=?",
                                Integer.class,
                                uid))
                .isEqualTo(before);
        write("/admin/aftersales/" + id + "/approve", a, decision);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2028-04-06T02:00:00Z");
        write("/dev/aftersales/" + id + "/refund", b, Map.of("scenario", "SUCCESS"));
        worker.run(100);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2027-10-10T02:00:00Z");
    }

    @Test
    void actualPaymentDeliveryRetriesAtomicallyAfterDatabaseFailure() throws Exception {
        var c = course("DAYS", 30);
        String id = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        jdbc.execute(
                "create trigger entitlement_test_failure before insert on entitlement for each row"
                    + " signal sqlstate '45000' set message_text='Injected transient DB failure'");
        try {
            worker.run(100);
        } finally {
            jdbc.execute("drop trigger entitlement_test_failure");
        }
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement where user_id=?",
                                Integer.class,
                                uid))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commerce_event_receipt where business_key=?"
                                        + " and event_type='ENTITLEMENT_DELIVER'",
                                Integer.class,
                                id))
                .isZero();
        jdbc.update(
                "update outbox_task set next_run_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) where"
                        + " task_type='ENTITLEMENT_DELIVER' and business_key=?",
                id);
        worker.run(100);
        assertThat(call("GET", "/orders/" + id, b, null, 200).path("orderStatus").asText())
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement where user_id=?",
                                Integer.class,
                                uid))
                .isEqualTo(1);
    }

    @Test
    void metadataEditsCannotRewriteFrozenCourseOrMembershipTerms() throws Exception {
        var c = course("DAYS", 30);
        String order = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        call(
                "PATCH",
                "/admin/courses/" + c.path("id").asText(),
                a,
                Map.of(
                        "version",
                        c.path("version").asLong(),
                        "productId",
                        c.path("productId").asText(),
                        "validityType",
                        "DAYS",
                        "validityDays",
                        1),
                200);
        deliver(order);
        assertThat(
                        jdbc.queryForObject(
                                        "select expires_at from entitlement where"
                                            + " order_item_id=(select id from order_item where"
                                            + " order_id=?)",
                                        java.sql.Timestamp.class,
                                        order)
                                .toInstant())
                .isEqualTo(Instant.parse("2026-11-05T02:00:00Z"));
        String p = member(6), memberOrder = paid(p, "2026-08-31T02:00:00Z");
        var product = call("GET", "/admin/products/" + p, a, null, 200);
        call(
                "POST",
                "/admin/products/" + p + "/unpublish",
                a,
                Map.of("version", product.path("version").asLong(), "reason", "DEV new terms"),
                200);
        jdbc.update("update membership_plan set status='DISABLED' where duration_months=12");
        long plan =
                jdbc.queryForObject(
                        "select id from membership_plan where product_id=?", Long.class, p);
        long planVersion =
                jdbc.queryForObject(
                        "select version from membership_plan where id=?", Long.class, plan);
        call(
                "PATCH",
                "/admin/membership/plans/" + plan,
                a,
                Map.of("version", planVersion, "durationMonths", 12, "status", "ENABLED"),
                200);
        deliver(memberOrder);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2027-02-28T02:00:00Z");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update membership_grant set duration_months=12 where"
                                            + " user_id=?",
                                        uid))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from entitlement_event where user_id=?", uid))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void actualRejectedFailedAndPartialRefundsKeepCourseEntitlement() throws Exception {
        var c = course("DAYS", 30);
        String order = paid(c.path("productId").asText(), "2026-10-06T02:00:00Z");
        deliver(order);
        var application =
                Map.of(
                        "type",
                        "REFUND_ONLY",
                        "reason",
                        "DEV compensation",
                        "evidenceAssetIds",
                        List.of(),
                        "requestedGoodsFen",
                        50,
                        "requestedFreightFen",
                        0);
        String rejected = write("/orders/" + order + "/refund", b, application).path("id").asText();
        write("/admin/aftersales/" + rejected + "/reject", a, Map.of("reason", "DEV rejected"));
        String failed = write("/orders/" + order + "/refund", b, application).path("id").asText();
        var decision =
                Map.of(
                        "goodsRefundFen",
                        50,
                        "freightRefundFen",
                        0,
                        "returnedQuantity",
                        0,
                        "reason",
                        "DEV partial");
        write("/admin/aftersales/" + failed + "/approve", a, decision);
        write("/dev/aftersales/" + failed + "/refund", b, Map.of("scenario", "FAILURE"));
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("ACTIVE");
        String partial = write("/orders/" + order + "/refund", b, application).path("id").asText();
        write("/admin/aftersales/" + partial + "/approve", a, decision);
        write("/dev/aftersales/" + partial + "/refund", b, Map.of("scenario", "SUCCESS"));
        worker.run(100);
        assertThat(call("GET", "/orders/" + order, b, null, 200).path("refundedGoodsFen").asLong())
                .isEqualTo(50);
        assertThat(
                        call("GET", "/products/" + c.path("productId").asText(), b, null, 200)
                                .at("/course/entitlement")
                                .asText())
                .isEqualTo("ACTIVE");
    }
}
