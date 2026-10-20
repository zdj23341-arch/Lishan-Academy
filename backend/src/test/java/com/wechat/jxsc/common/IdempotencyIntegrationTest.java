package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
    IdempotencyIntegrationTest.FixtureController.class,
    IdempotencyIntegrationTest.FixtureService.class
})
class IdempotencyIntegrationTest extends MySqlIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired IdempotencyService operations;
    @Autowired OperationAuditService audit;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void simultaneousRetriesCreateOneBusinessWriteAndPersistAnActorScopedResult() throws Exception {
        String token = login("buyer-active-member"), key = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<ResponseEntity<JsonNode>>>();
            for (int i = 0; i < 6; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return post(
                                            token,
                                            key,
                                            "TEST_WRITE",
                                            Map.of("quantity", 2),
                                            false,
                                            false);
                                }));
            start.countDown();
            String resource = null;
            for (var future : futures) {
                var result = future.get(20, TimeUnit.SECONDS);
                assertThat(result.getStatusCode().value()).isEqualTo(200);
                String actual = result.getBody().path("data").path("resourceId").asText();
                assertThat(actual).matches("[1-9][0-9]*");
                if (resource == null) resource = actual;
                else assertThat(actual).isEqualTo(resource);
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM system_setting WHERE setting_key LIKE ?",
                                Integer.class,
                                "idem-" + key + "-%"))
                .isEqualTo(1);
        var saved = get(token, "/operations/" + key + "?scope=TEST_WRITE");
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody().path("data").path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(saved.getBody().path("data").path("response").path("quantity").asInt())
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT TIMESTAMPDIFF(SECOND,created_at,expires_at) FROM"
                                        + " idempotency_record WHERE operation_key=?",
                                Long.class,
                                key))
                .isGreaterThanOrEqualTo(604800);
        var conflict = post(token, key, "TEST_WRITE", Map.of("quantity", 3), false, false);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        String other = login("buyer-expired-member");
        assertThat(get(other, "/operations/" + key + "?scope=TEST_WRITE").getStatusCode().value())
                .isEqualTo(404);
        assertThat(get(token, "/operations/" + key + "?scope=OTHER_WRITE").getStatusCode().value())
                .isEqualTo(404);
        assertThat(
                        post(other, key, "TEST_WRITE", Map.of("quantity", 3), false, false)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        post(token, key, "OTHER_WRITE", Map.of("quantity", 3), false, false)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM idempotency_record WHERE operation_key=?",
                                Integer.class,
                                key))
                .isEqualTo(3);
    }

    @Test
    void concurrentDifferentBodiesYieldOneWinnerAndOneConflict() throws Exception {
        String token = login("buyer"), key = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                token,
                                                key,
                                                "TEST_WRITE",
                                                Map.of("quantity", 1),
                                                false,
                                                false)
                                        .getStatusCode()
                                        .value();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                token,
                                                key,
                                                "TEST_WRITE",
                                                Map.of("quantity", 2),
                                                false,
                                                false)
                                        .getStatusCode()
                                        .value();
                            });
            start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void businessFailureAndOuterTransactionRollbackLeaveNoWritesAuditOrIdempotencyRecord() {
        String token = login("buyer");
        for (boolean outer : new boolean[] {false, true}) {
            String key = UUID.randomUUID().toString();
            assertThat(
                            post(token, key, "TEST_WRITE", Map.of("quantity", 1), !outer, outer)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(422);
            assertThat(
                            get(token, "/operations/" + key + "?scope=TEST_WRITE")
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(404);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM system_setting WHERE setting_key LIKE ?",
                                    Integer.class,
                                    "idem-" + key + "-%"))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM operation_audit_log WHERE reason=?",
                                    Integer.class,
                                    key))
                    .isZero();
            assertThat(
                            post(token, key, "TEST_WRITE", Map.of("quantity", 1), false, false)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(200);
        }
    }

    @Test
    void canonicalJsonAndValidationAndActorTypesAreStable() throws Exception {
        String token = login("buyer"), key = UUID.randomUUID().toString();
        var first = json.readTree("{\"quantity\":2,\"details\":{\"a\":1,\"b\":2}}");
        var reordered = json.readTree("{\"details\":{\"b\":2,\"a\":1},\"quantity\":2}");
        assertThat(post(token, key, "TEST_WRITE", first, false, false).getStatusCode().value())
                .isEqualTo(200);
        assertThat(post(token, key, "TEST_WRITE", reordered, false, false).getStatusCode().value())
                .isEqualTo(200);
        assertThat(
                        post(token, "invalid", "TEST_WRITE", first, false, false)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(get(token, "/operations/" + key).getStatusCode().value()).isEqualTo(400);
        assertThat(
                        get(token, "/operations/" + key + "?scope=TEST_WRITE&scope=OTHER")
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        String id = get(token, "/me").getBody().path("data").path("id").asText();
        var admin =
                new CurrentActor(CurrentActor.Type.ADMIN, Long.valueOf(id), null, Set.of(), false);
        assertThatThrownBy(() -> operations.lookup(admin, "TEST_WRITE", key))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("NOT_FOUND");
        var value = json.createObjectNode().put("accepted", true);
        var result =
                operations.execute(
                        admin,
                        "TEST_WRITE",
                        key,
                        first,
                        () ->
                                new IdempotencyService.Outcome(
                                        IdempotencyService.State.PROCESSING, "42", value));
        assertThat(result.state()).isEqualTo(IdempotencyService.State.PROCESSING);
        assertThat(operations.lookup(admin, "TEST_WRITE", key).resourceId()).isEqualTo("42");
    }

    @Test
    void auditIsAppendOnlyAndRequiresAnExistingTransaction() {
        var actor = new CurrentActor(CurrentActor.Type.ADMIN, 99L, null, Set.of(), false);
        String resource = UUID.randomUUID().toString();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                audit.append(
                                        actor,
                                        "TEST_AUDIT",
                                        "FIXTURE",
                                        resource,
                                        Map.of("value", 1),
                                        Map.of("value", 2),
                                        "Reason",
                                        "test-request"));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE operation_audit_log SET reason='changed' WHERE"
                                                + " resource_id=?",
                                        resource))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM operation_audit_log WHERE resource_id=?",
                                        resource))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                audit.append(
                                        actor,
                                        "TEST_AUDIT",
                                        "FIXTURE",
                                        resource,
                                        null,
                                        null,
                                        "Reason",
                                        "test-request"))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM operation_audit_log WHERE resource_id=?",
                                String.class,
                                resource))
                .isEqualTo("Reason");
    }

    String login(String fixture) {
        return http.postForEntity(
                        "/api/v1/auth/dev-login", Map.of("fixture", fixture), JsonNode.class)
                .getBody()
                .path("data")
                .path("accessToken")
                .asText();
    }

    ResponseEntity<JsonNode> get(String token, String path) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                "/api/v1" + path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    ResponseEntity<JsonNode> post(
            String token, String key, String scope, Object body, boolean fail, boolean outer) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/test/operations?scope=" + scope + "&fail=" + fail + "&outer=" + outer,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    @io.swagger.v3.oas.annotations.Hidden
    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class FixtureController {
        private final FixtureService writes;

        FixtureController(FixtureService writes) {
            this.writes = writes;
        }

        @PostMapping("/api/v1/test/operations")
        ApiResponse<IdempotencyService.Outcome> write(
                @RequestHeader("Idempotency-Key") String key,
                @RequestParam String scope,
                @RequestParam boolean fail,
                @RequestParam boolean outer,
                @RequestBody JsonNode body) {
            return ApiResponse.ok(
                    writes.write(CurrentActor.require(), scope, key, body, fail, outer));
        }
    }

    @org.springframework.boot.test.context.TestComponent
    static class FixtureService {
        @PersistenceContext EntityManager em;
        final IdempotencyService operations;
        final OperationAuditService audit;

        FixtureService(IdempotencyService operations, OperationAuditService audit) {
            this.operations = operations;
            this.audit = audit;
        }

        @Transactional
        public IdempotencyService.Outcome write(
                CurrentActor actor,
                String scope,
                String key,
                JsonNode body,
                boolean fail,
                boolean outer) {
            var result =
                    operations.execute(
                            actor,
                            scope,
                            key,
                            body,
                            () -> {
                                var setting =
                                        new SystemSetting(
                                                "idem-" + key + "-" + UUID.randomUUID(),
                                                body.toString());
                                em.persist(setting);
                                audit.append(
                                        actor,
                                        "TEST_WRITE",
                                        "FIXTURE",
                                        setting.getId().toString(),
                                        null,
                                        body,
                                        key,
                                        "test-request");
                                if (fail)
                                    throw new ApiException(
                                            HttpStatus.UNPROCESSABLE_ENTITY,
                                            "TEST_FAILURE",
                                            "Fixture failed");
                                return new IdempotencyService.Outcome(
                                        IdempotencyService.State.SUCCEEDED,
                                        setting.getId().toString(),
                                        body);
                            });
            if (outer)
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TEST_FAILURE",
                        "Outer transaction failed");
            return result;
        }
    }
}
