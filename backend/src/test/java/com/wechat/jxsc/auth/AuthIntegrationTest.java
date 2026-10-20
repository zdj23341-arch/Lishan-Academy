package com.wechat.jxsc.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ADMIN_BOOTSTRAP_LOGIN=task2-bootstrap",
            "ADMIN_BOOTSTRAP_PASSWORD=Temporary-Only-Test-42!"
        })
class AuthIntegrationTest extends MySqlIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminBootstrap bootstrap;

    @Test
    void fixtureLoginMeLogoutStoresOnlyHash() throws Exception {
        var login = post("/auth/dev-login", null, Map.of("fixture", "buyer-active-member"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        var data = login.getBody().path("data");
        String token = data.path("accessToken").asText();
        assertThat(java.util.Base64.getUrlDecoder().decode(token)).hasSizeGreaterThanOrEqualTo(32);
        assertThat(data.path("user").path("id").asText()).matches("[1-9][0-9]*");
        assertThat(data.path("user").path("distributor").asBoolean()).isFalse();
        assertThat(Instant.parse(data.path("expiresAt").asText()))
                .isBetween(Instant.now().plusSeconds(604790), Instant.now().plusSeconds(604810));
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM login_session WHERE token_hash=?",
                                Integer.class,
                                hash))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE"
                                        + " table_schema=DATABASE() AND table_name='login_session'",
                                String.class))
                .doesNotContain("token", "access_token");
        assertThat(get("/me", token).getBody().path("data").path("id"))
                .isEqualTo(data.path("user").path("id"));
        assertThat(get("/admin/me", token).getStatusCode().value()).isEqualTo(403);
        assertThat(post("/auth/logout", token, Map.of()).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/me", token).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void expiredRevokedAndDisabledSessionsAreRejected() {
        for (String condition :
                new String[] {
                    "expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND", "revoked_at=UTC_TIMESTAMP()"
                }) {
            String token = fixture("buyer");
            jdbc.update("UPDATE login_session SET " + condition + " WHERE actor_type='CUSTOMER'");
            assertThat(get("/me", token).getStatusCode().value()).isEqualTo(401);
        }
        String token = fixture("buyer-expired-member");
        String id = get("/me", token).getBody().path("data").path("id").asText();
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", id);
        try {
            assertThat(get("/me", token).getStatusCode().value()).isEqualTo(401);
            assertThat(
                            post("/auth/dev-login", null, Map.of("fixture", "buyer-expired-member"))
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(401);
        } finally {
            jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?", id);
        }
    }

    @Test
    void onlyWhitelistedFixturesAndValidBodiesAreAccepted() {
        assertThat(
                        post("/auth/dev-login", null, Map.of("fixture", "arbitrary-production-id"))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(post("/auth/dev-login", null, Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(
                        post("/auth/dev-login", null, Map.of("fixture", "buyer", "userId", "1"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        String first =
                get("/me", fixture("distributor-a")).getBody().path("data").path("id").asText();
        String second =
                get("/me", fixture("distributor-a")).getBody().path("data").path("id").asText();
        assertThat(second).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT app_id FROM user_wechat_identity WHERE user_id=?",
                                String.class,
                                first))
                .isEqualTo("DEV");
        assertThat(get("/me?page=1&page=2", fixture("buyer")).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void bootstrapMustChangePasswordBeforeReceivingPermissionsAndOldSessionsAreRevoked() {
        var login =
                post(
                        "/admin/auth/login",
                        null,
                        Map.of(
                                "loginName",
                                "task2-bootstrap",
                                "password",
                                "Temporary-Only-Test-42!"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String token = login.getBody().path("data").path("accessToken").asText();
        assertThat(login.getBody().path("data").path("passwordChangeRequired").asBoolean())
                .isTrue();
        assertThat(login.getBody().path("data").path("permissions")).isEmpty();
        assertThat(get("/admin/me", token).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/admin/audit-logs", token).getStatusCode().value()).isEqualTo(403);
        assertThat(
                        post(
                                        "/admin/auth/change-password",
                                        token,
                                        Map.of(
                                                "currentPassword",
                                                "wrong",
                                                "newPassword",
                                                "Changed-Only-Test-77!"))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        post(
                                        "/admin/auth/change-password",
                                        token,
                                        Map.of(
                                                "currentPassword",
                                                "Temporary-Only-Test-42!",
                                                "newPassword",
                                                "short"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        post(
                                        "/admin/auth/change-password",
                                        token,
                                        Map.of(
                                                "currentPassword",
                                                "Temporary-Only-Test-42!",
                                                "newPassword",
                                                "Changed-Only-Test-77!"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(get("/admin/me", token).getStatusCode().value()).isEqualTo(401);
        var changed =
                post(
                        "/admin/auth/login",
                        null,
                        Map.of(
                                "loginName",
                                "task2-bootstrap",
                                "password",
                                "Changed-Only-Test-77!"));
        String changedToken = changed.getBody().path("data").path("accessToken").asText();
        assertThat(changed.getBody().path("data").path("passwordChangeRequired").asBoolean())
                .isFalse();
        String unchangedHash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM admin_user WHERE login_name='task2-bootstrap'",
                        String.class);
        bootstrap.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM admin_user WHERE"
                                    + " login_name='task2-bootstrap'",
                                String.class))
                .isEqualTo(unchangedHash);
        assertThat(changed.getBody().path("data").path("permissions").toString())
                .contains("AUDIT_READ");
        assertThat(get("/admin/audit-logs", changedToken).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/admin/audit-logs?pageSize=101", changedToken).getStatusCode().value())
                .isEqualTo(400);
        assertThat(get("/admin/audit-logs?page=0", changedToken).getStatusCode().value())
                .isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM admin_user WHERE"
                                        + " login_name='task2-bootstrap'",
                                String.class))
                .startsWith("$2")
                .doesNotContain("Changed");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM operation_audit_log WHERE"
                                        + " action='ADMIN_PASSWORD_CHANGED'",
                                Integer.class))
                .isGreaterThanOrEqualTo(1);
        assertThat(post("/admin/auth/logout", changedToken, Map.of()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(get("/admin/me", changedToken).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void rolesDisabledAdminsAndRateLimitsAreEnforced() {
        String name = "editor-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " admin_user(login_name,password_hash,status,password_change_required,created_at,updated_at,version)"
                    + " VALUES (?,?,'ACTIVE',false,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                name,
                new BCryptPasswordEncoder().encode("Valid-Admin-Test-42!"));
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM admin_user WHERE login_name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO admin_user_role(admin_user_id,role_id) SELECT ?,id FROM admin_role"
                        + " WHERE code='CONTENT_EDITOR'",
                id);
        var login =
                post(
                        "/admin/auth/login",
                        null,
                        Map.of("loginName", name, "password", "Valid-Admin-Test-42!"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String token = login.getBody().path("data").path("accessToken").asText();
        assertThat(Instant.parse(login.getBody().path("data").path("expiresAt").asText()))
                .isBetween(Instant.now().plusSeconds(28790), Instant.now().plusSeconds(28810));
        assertThat(get("/admin/me", token).getBody().path("data").path("permissions").toString())
                .contains("CONTENT_EDIT")
                .doesNotContain("AUDIT_READ");
        assertThat(get("/admin/audit-logs", token).getStatusCode().value()).isEqualTo(403);
        assertThat(get("/me", token).getStatusCode().value()).isEqualTo(403);
        jdbc.update(
                "INSERT INTO admin_user_role(admin_user_id,role_id) SELECT ?,id FROM admin_role"
                        + " WHERE code='SUPER_ADMIN'",
                id);
        assertThat(get("/admin/audit-logs", token).getStatusCode().value()).isEqualTo(200);
        jdbc.update(
                "DELETE FROM admin_user_role WHERE admin_user_id=? AND role_id=(SELECT id FROM"
                        + " admin_role WHERE code='SUPER_ADMIN')",
                id);
        assertThat(get("/admin/audit-logs", token).getStatusCode().value()).isEqualTo(403);
        var badPassword =
                post("/admin/auth/login", null, Map.of("loginName", name, "password", "incorrect"));
        var badIdentity =
                post(
                        "/admin/auth/login",
                        null,
                        Map.of(
                                "loginName",
                                "missing-" + UUID.randomUUID(),
                                "password",
                                "incorrect"));
        assertThat(badPassword.getStatusCode())
                .isEqualTo(badIdentity.getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(badPassword.getBody().path("message"))
                .isEqualTo(badIdentity.getBody().path("message"));
        jdbc.update("UPDATE admin_user SET status='DISABLED' WHERE id=?", id);
        assertThat(get("/admin/me", token).getStatusCode().value()).isEqualTo(401);
        assertThat(
                        post(
                                        "/admin/auth/login",
                                        null,
                                        Map.of(
                                                "loginName",
                                                name,
                                                "password",
                                                "Valid-Admin-Test-42!"))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        String missing = "rate-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++)
            assertThat(
                            post(
                                            "/admin/auth/login",
                                            null,
                                            Map.of("loginName", missing, "password", "bad"))
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(401);
        var limited =
                post("/admin/auth/login", null, Map.of("loginName", missing, "password", "bad"));
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getBody().path("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void bcryptInputsCannotBeSilentlyTruncatedAndLogoutIsAudited() {
        String name = "bytes-" + UUID.randomUUID();
        String password = "密".repeat(24);
        jdbc.update(
                "INSERT INTO"
                    + " admin_user(login_name,password_hash,status,password_change_required,created_at,updated_at,version)"
                    + " VALUES (?,?,'ACTIVE',false,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                name,
                new BCryptPasswordEncoder().encode(password));
        assertThat(
                        post(
                                        "/admin/auth/login",
                                        null,
                                        Map.of("loginName", name, "password", password + "a"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        var login =
                post("/admin/auth/login", null, Map.of("loginName", name, "password", password));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String token = login.getBody().path("data").path("accessToken").asText();
        assertThat(
                        post(
                                        "/admin/auth/change-password",
                                        token,
                                        Map.of(
                                                "currentPassword",
                                                password + "a",
                                                "newPassword",
                                                "New-Test-Password-77!"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        post(
                                        "/admin/auth/change-password",
                                        token,
                                        Map.of(
                                                "currentPassword",
                                                password,
                                                "newPassword",
                                                "密".repeat(25)))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        String id = get("/admin/me", token).getBody().path("data").path("id").asText();
        assertThat(post("/admin/auth/logout", token, Map.of()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM operation_audit_log WHERE"
                                        + " action='ADMIN_LOGOUT' AND actor_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void invalidHttpRequestsUseConsistentErrors() {
        String token = fixture("buyer");
        var invalidMethod = post("/me", token, Map.of());
        assertThat(invalidMethod.getStatusCode().value()).isEqualTo(405);
        assertThat(invalidMethod.getBody().path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        var invalidMedia =
                http.exchange(
                        "/api/v1/auth/dev-login",
                        HttpMethod.POST,
                        new HttpEntity<>("buyer", headers),
                        JsonNode.class);
        assertThat(invalidMedia.getStatusCode().value()).isEqualTo(415);
        assertThat(invalidMedia.getBody().path("requestId").asText()).isNotBlank();
        assertThat(get("/me", "not-a-token").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void concurrentFixtureLoginsResolveOneIdentityAndAllIssueUsableSessions() throws Exception {
        for (int round = 0; round < 2; round++) {
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
                var futures =
                        new java.util.ArrayList<
                                java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
                for (int i = 0; i < 6; i++)
                    futures.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return post(
                                                "/auth/dev-login",
                                                null,
                                                Map.of("fixture", "distributor-b"));
                                    }));
                start.countDown();
                String user = null;
                for (var future : futures) {
                    var response = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    String id = response.getBody().path("data").path("user").path("id").asText();
                    if (user == null) user = id;
                    else assertThat(id).isEqualTo(user);
                    assertThat(
                                    get(
                                                    "/me",
                                                    response.getBody()
                                                            .path("data")
                                                            .path("accessToken")
                                                            .asText())
                                            .getStatusCode()
                                            .value())
                            .isEqualTo(200);
                }
            }
        }
    }

    String fixture(String name) {
        return post("/auth/dev-login", null, Map.of("fixture", name))
                .getBody()
                .path("data")
                .path("accessToken")
                .asText();
    }

    ResponseEntity<JsonNode> get(String path, String token) {
        return request(HttpMethod.GET, path, token, null);
    }

    ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return request(HttpMethod.POST, path, token, body);
    }

    ResponseEntity<JsonNode> request(HttpMethod method, String path, String token, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(
                "/api/v1" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
