package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.wechat.jxsc.paper.ContentAuthorization;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

@Import(FinanceSqlGate.class)
@SpringBootTest(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.connection-timeout=2000"
        })
class FinanceAuthorizationPoolIntegrationTest extends FinanceProvenanceIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired ContentAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;

    private Connection observerConnection() throws SQLException {
        var source = dataSource.unwrap(HikariDataSource.class);
        // Independent test observer only: never used by application authorization/classification.
        return DriverManager.getConnection(
                source.getJdbcUrl(), source.getUsername(), source.getPassword());
    }

    private void awaitSubjectWait(String table) throws Exception {
        try (var observer = observerConnection();
                var statement = observer.createStatement()) {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                try (var result =
                        statement.executeQuery(
                                "select count(*) from performance_schema.data_lock_waits w join"
                                        + " performance_schema.data_locks l on"
                                        + " l.engine_lock_id=w.requesting_engine_lock_id where"
                                        + " l.object_name='"
                                        + table
                                        + "'")) {
                    result.next();
                    if (result.getLong(1) > 0) return;
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Expected an actual " + table + " lock wait");
    }

    @Test
    void incompatibleOuterRepeatableReadFailsBeforeAuthorizationWrites() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(
                        () ->
                                transaction.execute(
                                        status ->
                                                authorization.file(
                                                        1,
                                                        1,
                                                        ContentAuthorization.Action.DOWNLOAD,
                                                        "test")))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class)
                .hasMessage("File authorization requires a READ COMMITTED transaction");
    }

    @Test
    void grantCommittedAfterInitialReadIsClassifiedAfterSubjectWait() throws Exception {
        String old = paid(member(6), "2026-10-10T02:00:00Z");
        deliver(old);
        String path = attachment();
        // Initiate the order before authorization admission; its callback grant remains below.
        String real = paid(member(6), "2026-10-10T02:00:00Z");
        jdbc.update("update store_order set channel='WECHAT_PAY' where id=?", real);
        jdbc.update("update payment_transaction set channel='WECHAT_PAY' where order_id=?", real);
        CountDownLatch beforeSubject = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean(true);
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (!after
                            && Thread.currentThread().getName().equals("authorization-subject-wait")
                            && sql.equals(
                                    "select user_id from entitlement_subject where user_id=? for"
                                            + " update")
                            && once.compareAndSet(true, false)) {
                        beforeSubject.countDown();
                        FinanceSqlGate.await(resume);
                    }
                };
        var executor =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "authorization-subject-wait"));
        try {
            var result =
                    executor.submit(() -> call("POST", path, b, Map.of("action", "DOWNLOAD"), 200));
            FinanceSqlGate.await(beforeSubject);
            deliver(real);
            refund(old, true, "pool-provenance-refund");
            try (var holder = observerConnection()) {
                holder.setAutoCommit(false);
                try (var lock =
                        holder.prepareStatement(
                                "select user_id from entitlement_subject where user_id=? for"
                                        + " update")) {
                    lock.setLong(1, uid);
                    lock.executeQuery();
                }
                resume.countDown();
                awaitSubjectWait("entitlement_subject");
                holder.commit();
            }
            assertThat(result.get(8, TimeUnit.SECONDS).path("url").asText()).isNotBlank();
            assertThat(
                            jdbc.queryForObject(
                                    "select p.source_kind from download_audit d join"
                                            + " download_provenance p on p.audit_id=d.id where"
                                            + " d.user_id=?",
                                    String.class,
                                    uid))
                    .isEqualTo("NON_DEV_PAYMENT");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from download_audit d join download_provenance"
                                            + " p on p.audit_id=d.id join membership_grant g on"
                                            + " g.id=p.membership_grant_id join order_item i on"
                                            + " i.id=g.order_item_id where d.user_id=? and"
                                            + " i.order_id=?",
                                    Long.class,
                                    uid,
                                    real))
                    .isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            FinanceSqlGate.observer = (sql, after) -> {};
        }
    }

    @Test
    void concurrentAuthorizationsCompleteWithEveryPoolConnectionOccupied() throws Exception {
        deliver(paid(member(6), "2026-10-10T02:00:00Z"));
        String path = attachment();
        CountDownLatch leaderAtGrant = new CountDownLatch(1),
                secondAtSubject = new CountDownLatch(1),
                resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (after
                            && sql.equals("select * from membership_grant where id=? for update")
                            && first.compareAndSet(true, false)) {
                        leaderAtGrant.countDown();
                        FinanceSqlGate.await(resume);
                    }
                    if (!after
                            && sql.equals(
                                    "select state from account_lifecycle where user_id=? for"
                                            + " update")
                            && leaderAtGrant.getCount() == 0) secondAtSubject.countDown();
                };
        Callable<Integer> download =
                () ->
                        mvc.perform(
                                        post("/api/v1" + path)
                                                .header("Authorization", "Bearer " + b)
                                                .contentType("application/json")
                                                .content(
                                                        json.writeValueAsBytes(
                                                                Map.of("action", "DOWNLOAD"))))
                                .andReturn()
                                .getResponse()
                                .getStatus();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(download);
            FinanceSqlGate.await(leaderAtGrant);
            var two = executor.submit(download);
            FinanceSqlGate.await(secondAtSubject);
            awaitSubjectWait("account_lifecycle");
            assertThat(
                            dataSource
                                    .unwrap(HikariDataSource.class)
                                    .getHikariPoolMXBean()
                                    .getActiveConnections())
                    .isEqualTo(2);
            resume.countDown();
            assertThat(List.of(one.get(8, TimeUnit.SECONDS), two.get(8, TimeUnit.SECONDS)))
                    .containsOnly(200);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from download_audit d join download_provenance"
                                            + " p on p.audit_id=d.id where d.user_id=? and"
                                            + " p.source_kind='DEV_PAYMENT'",
                                    Long.class,
                                    uid))
                    .isEqualTo(2);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            FinanceSqlGate.observer = (sql, after) -> {};
        }
    }
}
