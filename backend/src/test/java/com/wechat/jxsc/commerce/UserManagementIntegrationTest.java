package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.auth.AccountLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;

class UserManagementIntegrationTest extends EntitlementTestSupport {
    @Autowired AccountLifecycle lifecycle;
    @Autowired PlatformTransactionManager transactions;

    JsonNode user() throws Exception {
        return call("GET", "/admin/users?keyword=" + uid, a, null, 200).path("items").get(0);
    }

    @Test
    void directoryFiltersAndReturnsActualEntitlementWithoutPrivateIdentity() throws Exception {
        deliver(paid(member(6), "2026-10-10T02:00:00Z"));
        var u = user();
        assertThat(u.path("id").asText()).isEqualTo(Long.toString(uid));
        assertThat(u.path("status").asText()).isEqualTo("NORMAL");
        assertThat(u.path("username").isNull()).isTrue();
        assertThat(u.path("phone").isNull()).isTrue();
        assertThat(u.path("membership").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(u.has("openid")).isFalse();
        assertThat(u.has("phoneCipher")).isFalse();
        var p = call("GET", "/admin/users?keyword=" + uid + "&pageSize=1&page=2", a, null, 200);
        assertThat(p.path("items")).isEmpty();
        assertThat(p.path("total").asLong()).isEqualTo(1);
        assertThat(call("GET", "/admin/users?keyword=" + uid + "&status=BLOCKED", a, null, 200).path("total").asLong()).isZero();
        call("GET", "/admin/users?status=INVALID", a, null, 400);
        call("GET", "/admin/users", b, null, 403);
        call("GET", "/admin/users", admin("ORDER_OPERATOR"), null, 403);
        call("PATCH", "/admin/users/" + uid + "/status", admin("CONTENT_EDITOR"), Map.of("status", "BLOCKED", "version", 0, "reason", "test"), 403);
    }

    @Test
    void blockingRevokesSessionsAndAdmissionWhileRestoreDoesNotReviveOldTokens() throws Exception {
        var original = user();
        var blocked = call("PATCH", "/admin/users/" + uid + "/status", a,
                Map.of("status", "BLOCKED", "version", original.path("version").asLong(), "reason", "审核封禁"), 200);
        assertThat(blocked.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.path("version").asLong()).isEqualTo(original.path("version").asLong() + 1);
        call("GET", "/me", b, null, 401);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(s -> {
            lifecycle.requireActive(uid);
            return null;
        })).isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(call("PATCH", "/admin/users/" + uid + "/status", a,
                Map.of("status", "NORMAL", "version", original.path("version").asLong(), "reason", "过期版本"), 409).path("code").asText()).isEqualTo("VERSION_CONFLICT");
        var restored = call("PATCH", "/admin/users/" + uid + "/status", a,
                Map.of("status", "NORMAL", "version", blocked.path("version").asLong(), "reason", "审核恢复"), 200);
        assertThat(restored.path("status").asText()).isEqualTo("NORMAL");
        call("GET", "/me", b, null, 401);
        call("GET", "/me", buyer("buyer"), null, 200);
        assertThat(jdbc.queryForObject("select count(*) from operation_audit_log where action='USER_STATUS_CHANGE' and resource_id=?", Long.class, Long.toString(uid))).isEqualTo(2);
        call("PATCH", "/admin/users/" + uid + "/status", a, Map.of("status", "BLOCKED", "version", restored.path("version").asLong(), "reason", " "), 400);
    }

    @Test
    void closedAccountCannotBeRestoredAndConcurrentChangesHaveOneWinner() throws Exception {
        long v = user().path("version").asLong();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                ready.countDown(); start.await();
                var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/users/" + uid + "/status")
                    .header("Authorization", "Bearer " + a).contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("status", "BLOCKED", "version", v, "reason", "并发审核")));
                return mvc.perform(req).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { start.countDown(); pool.shutdownNow(); }
        var blocked = user();
        call("PATCH", "/admin/users/" + uid + "/status", a,
                Map.of("status", "NORMAL", "version", blocked.path("version").asLong(), "reason", "恢复后测试真实注销"), 200);
        b = buyer("buyer");
        write("/me/close", b, Map.of("confirm", "CLOSE_ACCOUNT", "forfeitRemainingAccess", true));
        var closed = user();
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(call("PATCH", "/admin/users/" + uid + "/status", a,
                Map.of("status", "NORMAL", "version", closed.path("version").asLong(), "reason", "禁止恢复"), 409).path("code").asText()).isEqualTo("ACCOUNT_CLOSED");
    }
}
