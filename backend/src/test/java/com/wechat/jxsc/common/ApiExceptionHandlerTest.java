package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();

    @Test
    void businessErrorPreservesStatusAndUsesRequestEnvelope() throws Exception {
        var result = mvc.perform(get("/business-error")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("SETTING_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Setting changed"))
                .andExpect(jsonPath("$.data").isEmpty()).andReturn();
        assertThat(new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("requestId").asText())
                .isNotBlank().isEqualTo(result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void unexpectedErrorReturnsSanitizedJsonEnvelope() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> mvc.perform(get("/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An internal error occurred"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())))
                .as("Unexpected exceptions must be translated to a sanitized JSON response").isNull();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/business-error")
        void business() { throw new ApiException(HttpStatus.CONFLICT, "SETTING_CONFLICT", "Setting changed"); }

        @GetMapping("/unexpected-error")
        void unexpected() { throw new IllegalStateException("private backend detail"); }
    }
}
