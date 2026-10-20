package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;

import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class MigrationTest extends MySqlIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagers;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;

    @Test
    void baseEntityPersistsUtcTimestampsStringIdAndRejectsStaleUpdate() {
        Long id;
        try (var entityManager = entityManagers.createEntityManager()) {
            entityManager.getTransaction().begin();
            var setting = new SystemSetting("test." + UUID.randomUUID(), "one");
            entityManager.persist(setting);
            entityManager.getTransaction().commit();
            id = setting.getId();
            assertThat(id).isPositive();
            assertThat(setting.getCreatedAt()).isNotNull();
            assertThat(setting.getUpdatedAt()).isNotNull();
            assertThat(setting.getVersion()).isZero();
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(json.valueToTree(setting).path("id").isTextual()).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT created_at FROM system_setting WHERE id=?",
                                    java.time.LocalDateTime.class,
                                    id))
                    .isEqualTo(
                            java.time.LocalDateTime.ofInstant(
                                    setting.getCreatedAt(), ZoneOffset.UTC));
        }
        try (var first = entityManagers.createEntityManager();
                var stale = entityManagers.createEntityManager()) {
            var firstCopy = first.find(SystemSetting.class, id);
            var staleCopy = stale.find(SystemSetting.class, id);
            first.getTransaction().begin();
            firstCopy.setSettingValue("two");
            first.getTransaction().commit();
            assertThat(firstCopy.getVersion()).isEqualTo(1);
            assertThat(firstCopy.getUpdatedAt()).isAfterOrEqualTo(firstCopy.getCreatedAt());
            stale.getTransaction().begin();
            staleCopy.setSettingValue("stale");
            assertThatThrownBy(stale::flush).isInstanceOf(OptimisticLockException.class);
            stale.getTransaction().rollback();
        }
    }

    @Test
    void populatedV17ReferralSurvivesAppendOnlyV18Upgrade() {
        String schema = com.wechat.jxsc.support.MySqlDatabase.createSchema();
        var source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        com.wechat.jxsc.support.MySqlDatabase.url(schema),
                        com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_USERNAME"),
                        com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_PASSWORD"));
        Flyway.configure()
                .dataSource(source)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false)
                .target("17")
                .load()
                .migrate();
        var old = new JdbcTemplate(source);
        old.update(
                "insert into user_account(id,nickname,status,created_at,updated_at) values(1,'V17"
                        + " test','ACTIVE',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        old.update(
                "insert into"
                    + " product(id,type,title,price_fen,status,detail_json,sort_no,created_at,updated_at)"
                    + " values(1,'COURSE','V17"
                    + " product',100,'DRAFT','[]',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        old.update(
                "insert into"
                        + " distributor(id,user_id,status,reason,approved_at,created_at,updated_at)"
                        + " values(1,1,'ACTIVE','Upgrade"
                        + " fixture',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        old.update(
                "insert into"
                    + " referral_code(code,product_id,distributor_id,enabled,expires_at,created_at)"
                    + " values('legacy-referral',1,1,true,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30"
                    + " DAY),UTC_TIMESTAMP())");
        var upgrade =
                Flyway.configure()
                        .dataSource(source)
                        .defaultSchema(schema)
                        .schemas(schema)
                        .createSchemas(false)
                        .target("18")
                        .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        var row = old.queryForMap("select * from referral_code where code='legacy-referral'");
        assertThat(row.get("enabled")).isEqualTo(true);
        assertThat(row.get("image_key")).isNull();
        assertThatThrownBy(
                        () ->
                                old.update(
                                        "update referral_code set"
                                            + " image_key='partial',image_version='v1',image_app_id='wx'"
                                            + " where code='legacy-referral'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(
                        error ->
                                assertThat(
                                                ((java.sql.SQLException) error.getCause())
                                                        .getErrorCode())
                                        .isEqualTo(3819));
    }

    @Test
    void emptySchemaMigratesOnceAndJpaMappingsValidate() {
        assertThat(flyway.info().current())
                .as("V1-V25 must migrate the initially empty dedicated schema")
                .isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("25");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        entityManagers.unwrap(SessionFactory.class).getSchemaManager().validateMappedObjects();
        assertThat(
                        jdbc.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE()",
                                String.class))
                .containsExactlyInAnyOrder(
                        "account_lifecycle",
                        "account_closure",
                        "retained_personal_data",
                        "economic_resolution",
                        "distribution_identity",
                        "wechat_identity_guard",
                        "wechat_statement_day",
                        "wechat_statement_availability",
                        "wechat_evidence",
                        "channel_statement",
                        "reconciliation_issue",
                        "distributor",
                        "distribution_rule_set",
                        "product_distributor_rule",
                        "referral_code",
                        "referral_touch",
                        "attribution",
                        "commission_account",
                        "commission_ledger",
                        "withdrawal_request",
                        "commission_account_ledger",
                        "withdrawal_event",
                        "transfer_simulation",
                        "finance_settings",
                        "admin_export",
                        "download_provenance",
                        "withdrawal_reconciliation",
                        "system_setting",
                        "entitlement_subject",
                        "entitlement_course_target",
                        "entitlement",
                        "membership_grant",
                        "entitlement_event",
                        "membership_record",
                        "membership_interval",
                        "learning_progress",
                        "content_capability",
                        "download_audit",
                        "flyway_schema_history",
                        "user_account",
                        "user_wechat_identity",
                        "admin_user",
                        "admin_role",
                        "admin_permission",
                        "admin_user_role",
                        "admin_role_permission",
                        "login_session",
                        "login_rate_limit",
                        "operation_audit_log",
                        "idempotency_record",
                        "file_asset",
                        "category",
                        "catalog_consistency_lock",
                        "product",
                        "product_category",
                        "book_inventory",
                        "inventory_ledger",
                        "course",
                        "course_chapter",
                        "membership_plan",
                        "paper",
                        "paper_attachment",
                        "article",
                        "home_draft",
                        "home_banner",
                        "home_shortcut",
                        "home_section",
                        "home_section_item",
                        "home_section_paper",
                        "home_layout",
                        "home_selection_snapshot",
                        "course_workflow_operation",
                        "dev_seed_run",
                        "user_address",
                        "shipping_rule",
                        "order_quote",
                        "store_order",
                        "order_item",
                        "order_address_snapshot",
                        "payment_transaction",
                        "aftersale_case",
                        "aftersale_evidence",
                        "refund_transaction",
                        "shipment",
                        "outbox_task",
                        "callback_inbox",
                        "dev_payment_state",
                        "commerce_event_receipt",
                        "financial_reconciliation",
                        "reconciliation_query");
    }

    @Test
    void appliedV24DraftUpgradesWithoutRepairOrChangingPublishedTime() {
        String schema = com.wechat.jxsc.support.MySqlDatabase.createSchema();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                com.wechat.jxsc.support.MySqlDatabase.url(schema),
                com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_USERNAME"),
                com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_PASSWORD"));
        var old = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .createSchemas(false).target("24").load();
        old.migrate();
        // Observed checksums from the user's already-applied database, not repaired history.
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select checksum from flyway_schema_history where version='22'", Integer.class))
                .isEqualTo(-983193997);
        assertThat(db.queryForObject("select checksum from flyway_schema_history where version='23'", Integer.class))
                .isEqualTo(-429532284);
        db.update("insert into product(id,type,title,price_fen,status,detail_json,sort_no,created_at,updated_at,version)"
                + " values(1,'COURSE','Keep existing course',100,'ON_SALE','[]',0,'2026-10-01 00:00:00','2026-10-02 00:00:00',3)");
        var upgrade = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .createSchemas(false).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select version from product where id=1", Long.class)).isEqualTo(3);
        var first = db.queryForObject("select first_published_at from product where id=1", java.time.LocalDateTime.class);
        assertThat(first).isEqualTo(java.time.LocalDateTime.parse("2026-10-01T00:00:00"));
        db.update("update product set title='Edited' where id=1");
        assertThat(db.queryForObject("select first_published_at from product where id=1", java.time.LocalDateTime.class)).isEqualTo(first);
        assertThat(db.queryForObject("select count(*) from information_schema.triggers where trigger_schema=database()"
                + " and trigger_name in ('product_first_publish_insert','paper_first_publish_insert')", Long.class)).isEqualTo(2);
    }

    @Test
    void populatedV21KeepsHomepageAndProductStateOnSimplificationUpgrade() {
        String schema = com.wechat.jxsc.support.MySqlDatabase.createSchema();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                com.wechat.jxsc.support.MySqlDatabase.url(schema),
                com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_USERNAME"),
                com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_PASSWORD"));
        Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .createSchemas(false).target("21").load().migrate();
        var db = new JdbcTemplate(source);
        db.update("insert into product(id,type,title,price_fen,status,detail_json,sort_no,created_at,updated_at,version)"
                + " values(1,'COURSE','Legacy course',100,'ON_SALE','[]',0,'2026-10-01 00:00:00','2026-10-02 00:00:00',3)");
        db.update("insert into home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version)"
                + " values('legacy','COURSE_GRID','Legacy section',0,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),2)");
        db.update("update home_draft set revision=7 where id=1");
        db.update("insert into home_section_item(section_id,product_id,sort_no,created_at,updated_at)"
                + " select id,1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) from home_section where item_key='legacy'");
        db.update("insert into home_layout(revision,modules_json,status,published_at,seed_key,created_at,updated_at)"
                + " values(6,?,'PUBLISHED',UTC_TIMESTAMP(6),'legacy-home',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                "{\"revision\":6,\"banners\":[],\"shortcuts\":[],\"sections\":[]}");
        String snapshot = db.queryForObject("select modules_json from home_layout where revision=6", String.class);
        var upgrade = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .createSchemas(false).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select revision from home_draft where id=1", Long.class)).isEqualTo(7);
        var section = db.queryForMap("select * from home_section where item_key='legacy'");
        assertThat(section.get("selection_mode")).isEqualTo("MANUAL");
        assertThat(((Number) section.get("display_limit")).intValue()).isEqualTo(4);
        assertThat(((Number) section.get("version")).intValue()).isEqualTo(2);
        assertThat(db.queryForObject("select status from product where id=1", String.class)).isEqualTo("ON_SALE");
        assertThat(db.queryForObject("select version from product where id=1", Long.class)).isEqualTo(3);
        assertThat(db.queryForObject("select first_published_at from product where id=1", java.time.LocalDateTime.class))
                .isEqualTo(java.time.LocalDateTime.parse("2026-10-01T00:00:00"));
        assertThat(db.queryForObject("select count(*) from home_selection_snapshot", Long.class)).isZero();
        assertThat(db.queryForObject("select modules_json from home_layout where revision=6", String.class)).isEqualTo(snapshot);
        assertThat(db.queryForObject("select product_id from home_section_item", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from course_workflow_operation", Long.class)).isZero();
    }

    @Test
    void populatedV18UpgradesWithoutInventingMetadataOrChangingUserState() {
        String schema = com.wechat.jxsc.support.MySqlDatabase.createSchema();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                com.wechat.jxsc.support.MySqlDatabase.url(schema),
                com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_USERNAME"),
                com.wechat.jxsc.support.MySqlDatabase.environment("MYSQL_TEST_PASSWORD"));
        Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .createSchemas(false).target("18").load().migrate();
        var db = new JdbcTemplate(source);
        db.update("insert into article(id,title,body_json,status,published_at,created_at,updated_at,version)"
                + " values(1,'Legacy article','[]','PUBLISHED','2026-10-01 00:00:00','2026-10-01 00:00:00','2026-10-02 00:00:00',3)");
        db.update("insert into user_account(id,nickname,status,created_at,updated_at,version)"
                + " values(1,'Legacy customer','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),4)");
        db.update("insert into user_account(id,nickname,status,created_at,updated_at,version)"
                + " values(2,'Legacy blocked','DISABLED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),5)");
        db.update("insert into user_account(id,nickname,status,created_at,updated_at,version)"
                + " values(3,'Legacy closed','ANONYMIZED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),6)");
        db.update("insert into account_closure(user_id,forfeited_access,request_id) values(3,true,'legacy-closed')");
        db.update("insert into login_session(token_hash,actor_type,actor_id,user_id,expires_at,created_at,updated_at)"
                + " values(?,'CUSTOMER',2,2,'2099-01-01 00:00:00',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "a".repeat(64));
        db.update("insert into login_session(token_hash,actor_type,actor_id,user_id,expires_at,revoked_at,created_at,updated_at)"
                + " values(?,'CUSTOMER',2,2,'2099-01-01 00:00:00','2026-10-01 00:00:00',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "b".repeat(64));
        db.update("insert into login_session(token_hash,actor_type,actor_id,user_id,expires_at,created_at,updated_at)"
                + " values(?,'CUSTOMER',1,1,'2099-01-01 00:00:00',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "c".repeat(64));
        var upgrade = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).createSchemas(false).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        var article = db.queryForMap("select * from article where id=1");
        assertThat(article.get("title")).isEqualTo("Legacy article");
        assertThat(article.get("author_name")).isNull();
        assertThat(article.get("category_name")).isNull();
        assertThat(((Number)article.get("version")).longValue()).isEqualTo(3);
        assertThat(db.queryForObject("select status from user_account where id=1", String.class)).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("select version from user_account where id=1", Long.class)).isEqualTo(4);
        assertThat(db.queryForObject("select status from user_account where id=2", String.class)).isEqualTo("DISABLED");
        assertThat(db.queryForObject("select version from user_account where id=2", Long.class)).isEqualTo(5);
        assertThat(db.queryForObject("select state from account_lifecycle where user_id=2", String.class)).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("select revoked_at from login_session where token_hash=?", java.sql.Timestamp.class, "a".repeat(64))).isNotNull();
        assertThat(db.queryForObject("select revoked_at from login_session where token_hash=?", java.sql.Timestamp.class, "b".repeat(64)))
                .isEqualTo(java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(db.queryForObject("select revoked_at from login_session where token_hash=?", java.sql.Timestamp.class, "c".repeat(64))).isNull();
        assertThat(db.queryForObject("select state from account_lifecycle where user_id=3", String.class)).isEqualTo("CLOSED");
        db.update("insert into user_account(id,nickname,status,created_at,updated_at)"
                + " values(4,'New blocked','DISABLED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        assertThat(db.queryForObject("select state from account_lifecycle where user_id=4", String.class)).isEqualTo("ACTIVE");
        assertThat(db.queryForList("select r.code from admin_role r join admin_role_permission rp on rp.role_id=r.id"
                + " join admin_permission p on p.id=rp.permission_id where p.code='USER_MANAGE'", String.class))
                .containsExactly("SUPER_ADMIN");
    }
}
