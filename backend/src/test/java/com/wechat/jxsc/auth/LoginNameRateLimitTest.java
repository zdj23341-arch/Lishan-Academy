package com.wechat.jxsc.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginNameRateLimitTest extends MySqlIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void trailingSpaceAliasesCannotObtainExtraAttemptsForTheSameMySqlAccount() {
        String name = "pad-space-" + UUID.randomUUID();
        String password = "Rate-Limit-Test-42!";
        jdbc.update(
                "INSERT INTO"
                    + " admin_user(login_name,password_hash,status,password_change_required,created_at,updated_at,version)"
                    + " VALUES (?,?,'ACTIVE',false,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                name,
                new BCryptPasswordEncoder().encode(password));
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM admin_user WHERE login_name=?", Long.class, name);
        for (String suffix : new String[] {" ", "  ", "   "}) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT id FROM admin_user WHERE login_name=?",
                                    Long.class,
                                    name + suffix))
                    .isEqualTo(id);
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            var denied =
                    http.postForEntity(
                            "/api/v1/admin/auth/login",
                            Map.of("loginName", name, "password", "incorrect"),
                            JsonNode.class);
            assertThat(denied.getStatusCode().value()).isEqualTo(401);
        }
        // Even a correct password must not reach authentication after the account bucket is
        // exhausted.
        for (String suffix : new String[] {" ", "  ", "   ", ""}) {
            var limited =
                    http.postForEntity(
                            "/api/v1/admin/auth/login",
                            Map.of("loginName", name + suffix, "password", password),
                            JsonNode.class);
            assertThat(limited.getStatusCode().value()).isEqualTo(429);
            assertThat(limited.getBody().path("code").asText()).isEqualTo("RATE_LIMITED");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM login_session WHERE admin_user_id=?",
                                Integer.class,
                                id))
                .isZero();
    }
}
