package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionPreflightTest {
    @Test
    void actualCapabilityReadinessSeparatesRecoveryAndCreation() throws Exception {
        var provider =
                org.mockito.Mockito.mock(com.wechat.jxsc.distribution.TransferProvider.class);
        org.mockito.Mockito.when(provider.recoveryReady()).thenReturn(true);
        var env = new org.springframework.mock.env.MockEnvironment();
        var check = new RuntimeSafetyGuard().validateProductionCapabilities(provider, env);
        assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class);
        env.withProperty("jxsc.production.recovery-only", "true");
        check.run(null);
        org.mockito.Mockito.when(provider.recoveryReady()).thenReturn(false);
        assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalProductionRejectsMalformedCreateCapability() {
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("jxsc.wechat.transfer.scene-reports", "[]")
                        .withProperty("jxsc.wechat.transfer.max-fen", "0")
                        .withProperty("jxsc.wechat.transfer.remark", "");
        assertThat(ProductionPreflight.errors(env))
                .contains(
                        "jxsc.wechat.transfer.scene-reports",
                        "jxsc.wechat.transfer.max-fen",
                        "jxsc.wechat.transfer.remark");
    }

    @Test
    void missingProductionDependenciesFailTogetherWithoutSecretValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeSafetyGuard.class)
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "jxsc.production.validate=true",
                        "spring.datasource.password=NEVER_PRINT_THIS_SECRET")
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure())
                                    .hasStackTraceContaining("spring.datasource.url")
                                    .hasStackTraceContaining("jxsc.scanner.host");
                            assertThat(c.getStartupFailure().toString())
                                    .doesNotContain("NEVER_PRINT_THIS_SECRET");
                        });
    }
}
