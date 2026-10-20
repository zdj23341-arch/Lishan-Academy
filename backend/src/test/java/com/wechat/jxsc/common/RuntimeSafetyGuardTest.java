package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

class RuntimeSafetyGuardTest {
    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(RuntimeSafetyGuard.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void productionRejectsDevelopmentProfileCombination(String unsafe) {
        context("prod", unsafe).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "payment", "transfer", "storage", "video", "logistics"})
    void productionRejectsEveryDevelopmentProvider(String provider) {
        context("prod")
                .withPropertyValues("jxsc.providers." + provider + "=DEV")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "payment", "transfer", "storage", "video", "logistics"})
    void environmentOverrideCannotEnableDevelopmentProviderInProduction(String provider) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(provider.toUpperCase() + "_PROVIDER_MODE", "DEV");
        context("prod")
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new SystemEnvironmentPropertySource(
                                                        "unsafe-override", variables)))
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev", "test"})
    void unknownProviderModeFailsInEveryProfile(String profile) {
        context(profile)
                .withPropertyValues("jxsc.providers.payment=TYPO")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contentSeedCannotBeEnabledInProduction() {
        context("prod")
                .withPropertyValues("jxsc.dev-seed.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void realModeStillRequiresProductionDependenciesAndExplicitDevelopmentIsAllowed() {
        context("prod").run(context -> assertThat(context).hasFailed());
        context("dev")
                .withPropertyValues("jxsc.providers.payment=DEV")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
