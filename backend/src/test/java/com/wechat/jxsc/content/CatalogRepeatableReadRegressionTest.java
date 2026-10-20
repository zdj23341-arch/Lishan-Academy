package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechat.jxsc.auth.SessionService;
import com.wechat.jxsc.catalog.*;
import com.wechat.jxsc.common.ApiException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

class CatalogRepeatableReadRegressionTest extends ContentHttpSupport {
    @Autowired CategoryService categories;
    @Autowired ProductService products;
    @Autowired SessionService sessions;
    @Autowired PlatformTransactionManager manager;

    @ParameterizedTest
    @ValueSource(strings = {"ASSIGN_AFTER_TYPE_CHANGE", "RETYPE_AFTER_REFERENCE"})
    void commandRechecksCurrentCategoryFactsAfterWaitingWithAnOldSnapshot(String scenario)
            throws Exception {
        String token = admin("CONTENT_EDITOR");
        var category =
                call(
                        "POST",
                        "/admin/categories",
                        token,
                        Map.of(
                                "version",
                                0,
                                "name",
                                "Snapshot parent",
                                "type",
                                "BOOK",
                                "sortNo",
                                0,
                                "enabled",
                                true),
                        200);
        long id = category.path("id").asLong();
        var book = product("BOOK", "Snapshot assignment", 100);
        book.put("categoryIds", List.of("" + id));
        var createBook = json.convertValue(book, CatalogDtos.ProductWrite.class);
        var changeType =
                new CatalogDtos.CategoryWrite(
                        0L, "Snapshot parent", Product.Type.COURSE, null, 0, true);
        Runnable assign = () -> products.save(null, createBook);
        Runnable retype = () -> categories.save(id, changeType);
        Runnable writerCommand = scenario.equals("ASSIGN_AFTER_TYPE_CHANGE") ? retype : assign;
        Runnable readerCommand = scenario.equals("ASSIGN_AFTER_TYPE_CHANGE") ? assign : retype;
        var snapshotReady = new CountDownLatch(1);
        var writerHasWritten = new CountDownLatch(1);
        var allowWriterCommit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reader =
                    pool.submit(
                            () ->
                                    asAdmin(
                                            token,
                                            () ->
                                                    transaction()
                                                            .execute(
                                                                    status -> {
                                                                        assertThat(
                                                                                        categories
                                                                                                .get(
                                                                                                        id)
                                                                                                .type())
                                                                                .isEqualTo(
                                                                                        Product.Type
                                                                                                .BOOK);
                                                                        snapshotReady.countDown();
                                                                        await(writerHasWritten);
                                                                        readerCommand.run();
                                                                        return 200;
                                                                    })));
            var writer =
                    pool.submit(
                            () ->
                                    asAdmin(
                                            token,
                                            () -> {
                                                await(snapshotReady);
                                                return transaction()
                                                        .execute(
                                                                status -> {
                                                                    writerCommand.run();
                                                                    writerHasWritten.countDown();
                                                                    await(allowWriterCommit);
                                                                    return 200;
                                                                });
                                            }));
            try {
                await(writerHasWritten);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                while (System.nanoTime() < deadline) {
                    waiting =
                            jdbc.queryForObject(
                                    "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM"
                                            + " performance_schema.data_lock_waits w JOIN"
                                            + " performance_schema.data_locks l ON"
                                            + " l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND"
                                            + " l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE()",
                                    Integer.class);
                    if (waiting >= 1) break;
                    Thread.sleep(20);
                }
                assertThat(waiting)
                        .as("The stale-snapshot command waits on the in-flight writer")
                        .isGreaterThanOrEqualTo(1);
            } finally {
                allowWriterCommit.countDown();
            }
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(reader.get(10, TimeUnit.SECONDS)).isEqualTo(400);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM product_category pc JOIN product p ON"
                                    + " p.id=pc.product_id JOIN category c ON c.id=pc.category_id"
                                    + " WHERE c.id=? AND p.type<>c.type",
                                Integer.class,
                                id))
                .isZero();
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }

    private int asAdmin(String token, Callable<Integer> work) throws Exception {
        var actor = sessions.authenticate(token);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return work.call();
        } catch (ApiException error) {
            return error.getStatus().value();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Timed out coordinating real transactions");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
