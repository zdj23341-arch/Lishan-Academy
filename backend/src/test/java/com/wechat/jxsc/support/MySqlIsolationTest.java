package com.wechat.jxsc.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Overrides are exercised only against schemas owned by the disposable MySQL process. */
class MySqlIsolationTest {
    @ParameterizedTest
    @ValueSource(strings = {"connection", "credentials", "schemas"})
    void inheritedFlywayOverridesCannotRedirectMigrations(String override) throws Exception {
        boolean schemaOverride = override.equals("schemas");
        String decoy = schemaOverride ? "jxsc_test_" + UUID.randomUUID().toString().replace("-", "")
                : MySqlDatabase.createSchema();
        Map<String, Object> inherited = new LinkedHashMap<>();
        if (schemaOverride) {
            inherited.put("SPRING_FLYWAY_SCHEMAS_0", decoy);
            inherited.put("SPRING_FLYWAY_DEFAULT_SCHEMA", decoy);
            inherited.put("SPRING_FLYWAY_CREATE_SCHEMAS", "true");
        } else {
            inherited.put("SPRING_FLYWAY_URL", MySqlDatabase.url(decoy));
            inherited.put("SPRING_FLYWAY_USER", override.equals("credentials") ? "nonexistent_poison_user"
                    : MySqlDatabase.environment("MYSQL_TEST_USERNAME"));
            inherited.put("SPRING_FLYWAY_PASSWORD", override.equals("credentials") ? "not_a_password"
                    : MySqlDatabase.environment("MYSQL_TEST_PASSWORD"));
        }
        Map<String, Object> owned = new LinkedHashMap<>();
        MySqlDatabase.configure((name, value) -> owned.put(name, value.get()));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .addFirst(new SystemEnvironmentPropertySource("inherited-flyway-config", inherited));
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-test-config", owned));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_name='system_setting'", Integer.class))
                            .as("V1 must migrate the verified datasource schema").isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",
                            Integer.class, decoy)).as("No migration may write into the decoy schema").isZero();
                    if (schemaOverride) {
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                                Integer.class, decoy)).as("Inherited create-schemas must not create a decoy schema").isZero();
                    }
                });
    }
}
