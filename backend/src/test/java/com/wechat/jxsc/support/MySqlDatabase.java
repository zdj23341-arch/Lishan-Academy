package com.wechat.jxsc.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Test-only access to the disposable process created by scripts/with_local_mysql.py. */
public final class MySqlDatabase {
    private MySqlDatabase() {}

    public static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Run MySQL integration tests through scripts/with_local_mysql.py (missing " + name + ")");
        }
        return value;
    }

    public static Connection connect() throws SQLException {
        String url = environment("MYSQL_TEST_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/jxsc_test_[a-f0-9]+\\?.*")) {
            throw new IllegalStateException("Refusing a non-isolated MySQL test URL");
        }
        Connection connection = DriverManager.getConnection(url, environment("MYSQL_TEST_USERNAME"), environment("MYSQL_TEST_PASSWORD"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT @@datadir")) {
            rows.next();
            if (!Path.of(rows.getString(1)).normalize().equals(Path.of(environment("MYSQL_TEST_OWNED_DATADIR")).normalize())) {
                throw new IllegalStateException("MySQL process ownership does not match the test runner");
            }
        } catch (RuntimeException | SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    public static String createSchema() {
        String schema = "jxsc_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException error) {
            throw new IllegalStateException("Could not create isolated test schema", error);
        }
        return schema;
    }

    public static String url(String schema) {
        if (!schema.matches("jxsc_test_[a-f0-9]+")) {
            throw new IllegalArgumentException("Unexpected test schema name");
        }
        return environment("MYSQL_TEST_URL").replaceFirst("/jxsc_test_[a-f0-9]+\\?", "/" + schema + "?");
    }

    public static void configure(DynamicPropertyRegistry registry) {
        String schema = createSchema();
        registry.add("spring.datasource.url", () -> url(schema));
        registry.add("spring.datasource.username", () -> environment("MYSQL_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> environment("MYSQL_TEST_PASSWORD"));
        // Flyway can construct its own datasource, independently of Spring's JPA
        // datasource. Pin all connection and schema selectors at dynamic-property
        // precedence before Flyway initializes, including indexed schema overrides.
        registry.add("spring.flyway.url", () -> url(schema));
        registry.add("spring.flyway.user", () -> environment("MYSQL_TEST_USERNAME"));
        registry.add("spring.flyway.password", () -> environment("MYSQL_TEST_PASSWORD"));
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.create-schemas", () -> false);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.clean-disabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
}
