package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class FoundationApiTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;

    @Test
    void generatedPasswordLoginIsAbsent() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    @WithMockUser
    void authenticatedPrivateRouteIsDeniedUntilAuthorizationIsImplemented() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void healthReturnsStatusOnlyWithServerRequestId() throws Exception {
        var result = mvc.perform(get("/api/v1/health").header("X-Request-Id", "client-controlled-id"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP")).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data").size()).isEqualTo(1);
        assertThat(body.path("requestId").asText()).isNotBlank().isNotEqualTo("client-controlled-id")
                .isEqualTo(result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void privateRouteWithoutCredentialsReturnsJson401() throws Exception {
        var result = mvc.perform(get("/api/v1/me").accept("text/html"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("requestId").asText())
                .isNotBlank().isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void testProfileCanReadOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/health']").exists());
    }
}
