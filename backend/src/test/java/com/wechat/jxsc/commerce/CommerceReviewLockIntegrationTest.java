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

import java.util.*;
import java.util.concurrent.*;

class CommerceReviewLockIntegrationTest extends CommerceTestSupport {
    @Autowired OrderService orders;
    @Autowired AccountLifecycle lifecycle;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void sameCustomerPreviewWaitsBeforeProductWhilePriorQuoteCreates() throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer"), p = book(a, 3, 2000);
        String address = address(b).path("id").asText();
        String quote = quote(b, p, address, 1).path("quoteId").asText();
        long user =
                jdbc.queryForObject(
                        "select user_id from user_address where id=?", Long.class, address);
        var customerLocked = new CountDownLatch(1);
        var previewConnection = new CompletableFuture<Long>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var create =
                    pool.submit(
                            () -> {
                                authenticate(user);
                                try {
                                    return new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        lifecycle.requireActive(user);
                                                        jdbc.queryForObject(
                                                                "select id from user_account where"
                                                                        + " id=? for update",
                                                                Long.class,
                                                                user);
                                                        customerLocked.countDown();
                                                        long connection;
                                                        try {
                                                            connection =
                                                                    previewConnection.get(
                                                                            10, TimeUnit.SECONDS);
                                                        } catch (Exception e) {
                                                            throw new AssertionError(e);
                                                        }
                                                        long deadline =
                                                                System.nanoTime()
                                                                        + TimeUnit.SECONDS.toNanos(
                                                                                10);
                                                        boolean waiting = false;
                                                        while (System.nanoTime() < deadline) {
                                                            waiting =
                                                                    jdbc.queryForObject(
                                                                                    "select"
                                                                                        + " count(*)"
                                                                                        + " from"
                                                                                        + " performance_schema.data_lock_waits"
                                                                                        + " w join"
                                                                                        + " performance_schema.threads"
                                                                                        + " t on"
                                                                                        + " t.thread_id=w.requesting_thread_id"
                                                                                        + " where"
                                                                                        + " t.processlist_id=?",
                                                                                    Integer.class,
                                                                                    connection)
                                                                            > 0;
                                                            if (waiting) break;
                                                            java.util.concurrent.locks.LockSupport
                                                                    .parkNanos(
                                                                            TimeUnit.MILLISECONDS
                                                                                    .toNanos(10));
                                                        }
                                                        assertThat(waiting)
                                                                .as(
                                                                        "Preview reached a real"
                                                                            + " InnoDB lock wait")
                                                                .isTrue();
                                                        return orders.create(
                                                                new OrderDtos.Create(quote, null),
                                                                UUID.randomUUID().toString());
                                                    });
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            var preview =
                    pool.submit(
                            () -> {
                                assertThat(customerLocked.await(10, TimeUnit.SECONDS)).isTrue();
                                authenticate(user);
                                try {
                                    return new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        previewConnection.complete(
                                                                jdbc.queryForObject(
                                                                        "select connection_id()",
                                                                        Long.class));
                                                        return orders.preview(
                                                                new OrderDtos.Preview(
                                                                        p, 1, address));
                                                    });
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            assertThat(create.get(25, TimeUnit.SECONDS).path("id").asText()).isNotBlank();
            assertThat(preview.get(25, TimeUnit.SECONDS).quoteId()).isNotBlank();
            assertThat(
                            jdbc.queryForObject(
                                    "select reserved from book_inventory where product_id=?",
                                    Integer.class,
                                    p))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void authenticate(long user) {
        var actor = new CurrentActor(CurrentActor.Type.CUSTOMER, user, null, Set.of(), false);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }
}
