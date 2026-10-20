package com.wechat.jxsc.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevRegistrationTest {
    @Test
    void developmentControllerAndServiceAreAbsentInEveryUnsafeCombination() {
        for (String[] profiles :
                new String[][] {{"prod"}, {"prod", "dev"}, {"prod", "test"}, {"staging"}}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(DevLoginController.class, DevFixtureService.class)
                    .withInitializer(
                            context -> context.getEnvironment().setActiveProfiles(profiles))
                    .withPropertyValues("jxsc.providers.identity=DEV")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context)
                                        .doesNotHaveBean(DevLoginController.class)
                                        .doesNotHaveBean(DevFixtureService.class);
                            });
        }
        new ApplicationContextRunner()
                .withUserConfiguration(DevLoginController.class, DevFixtureService.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("jxsc.providers.identity=REAL")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .doesNotHaveBean(DevLoginController.class)
                                    .doesNotHaveBean(DevFixtureService.class);
                        });
    }
}
