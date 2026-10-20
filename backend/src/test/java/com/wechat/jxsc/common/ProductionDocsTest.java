package com.wechat.jxsc.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles(profiles = "prod", inheritProfiles = false)
@WebMvcTest(
        value = HealthController.class,
        excludeAutoConfiguration =
                org.springframework.boot.autoconfigure.security.servlet
                        .UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(
        classes = {HealthController.class, SecurityConfiguration.class, RequestIdFilter.class})
class ProductionDocsTest {
    @MockitoBean com.wechat.jxsc.auth.SessionService sessions;

    @Autowired MockMvc mvc;

    @Test
    void documentationRemainsPrivateEvenWhenEnvironmentEnablesGeneration() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }
}
