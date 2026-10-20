package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.catalog.CatalogConsistencyGuard;
import com.wechat.jxsc.common.content.DevelopmentContentChineseUpgrade;
import com.wechat.jxsc.common.content.DevelopmentContentSeed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ChineseUpgradeRegistrationTest {
    @Test
    void realIdentityCanUpgradeOldExamplesWithoutEnablingDevelopmentSeeding() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DevelopmentContentChineseUpgrade.class, DevelopmentContentSeed.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                // Register the boundary double directly: Spring must not inject its JPA fields.
                .withInitializer(
                        c ->
                                c.getBeanFactory()
                                        .registerSingleton(
                                                "catalogConsistencyGuard",
                                                mock(CatalogConsistencyGuard.class)))
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("dev", "local"))
                .withPropertyValues(
                        "jxsc.providers.identity=REAL",
                        "jxsc.providers.storage=DEV",
                        "jxsc.dev-seed.enabled=true")
                .run(
                        c -> {
                            assertThat(c)
                                    .hasNotFailed()
                                    .hasSingleBean(DevelopmentContentChineseUpgrade.class)
                                    .doesNotHaveBean(DevelopmentContentSeed.class);
                        });
    }

    @Test
    void upgradeIsAbsentFromProductionEvenWithDevelopmentProfileAlsoEnabled() {
        for (String[] profiles :
                new String[][] {{"prod"}, {"dev", "prod"}, {"test", "prod"}, {"staging"}}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(DevelopmentContentChineseUpgrade.class)
                    .withInitializer(c -> c.getEnvironment().setActiveProfiles(profiles))
                    .run(
                            c ->
                                    assertThat(c)
                                            .hasNotFailed()
                                            .doesNotHaveBean(
                                                    DevelopmentContentChineseUpgrade.class));
        }
    }
}
