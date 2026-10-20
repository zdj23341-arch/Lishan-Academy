package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RuntimeReadinessIntegrationTest extends ContentHttpSupport {
    @Test
    void readinessAndOperatorQueueVisibilityUseSeparatePermissions() throws Exception {
        assertThat(call("GET", "/health/ready", null, null, 200).path("status").asText())
                .isEqualTo("UP");
        call("GET", "/admin/runtime", buyer("buyer"), null, 403);
        assertThat(call("GET", "/admin/runtime", admin("SUPER_ADMIN"), null, 200).has("tasks"))
                .isTrue();
    }
}
