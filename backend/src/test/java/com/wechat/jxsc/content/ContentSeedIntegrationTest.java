package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.*;

@SpringBootTest(properties = "jxsc.dev-seed.enabled=true")
class ContentSeedIntegrationTest extends ContentHttpSupport {
    @Autowired ApplicationContext context;

    @Test
    void explicitlyEnabledSeedsAreSelfAuthoredIdempotentAndPreserveEdits() throws Exception {
        var home = call("GET", "/home", null, null, 200);
        assertThat(home.path("banners").size()).isEqualTo(3);
        assertThat(home.path("shortcuts").size()).isEqualTo(6);
        var labels = new ArrayList<String>();
        home.path("shortcuts").forEach(s -> labels.add(s.path("label").asText()));
        assertThat(labels)
                .containsExactly("视频课程", "作文书籍", "会员试卷", "我的学习", "会员中心", "订单物流");
        assertThat(call("GET", "/config/public", null, null, 200).path("storeName").asText())
                .isEqualTo("力山学堂");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM product WHERE type='COURSE' AND seed_key IS"
                                        + " NOT NULL",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM product WHERE type='BOOK' AND seed_key IS NOT"
                                        + " NULL",
                                Integer.class))
                .isGreaterThanOrEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT duration_months FROM membership_plan ORDER BY"
                                        + " duration_months",
                                Integer.class))
                .containsExactly(6, 12);
        assertThat(
                        jdbc.queryForList(
                                "SELECT extension FROM file_asset WHERE purpose='PAPER_ATTACHMENT'"
                                        + " ORDER BY extension",
                                String.class))
                .containsExactly("DOCX", "PDF", "PNG");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM file_asset WHERE status<>'READY'",
                                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM user_wechat_identity WHERE app_id='DEV' AND"
                                        + " openid IN ('distributor-a','distributor-b')",
                                Integer.class))
                .isEqualTo(2);
        jdbc.update("UPDATE product SET title='User edited title' WHERE seed_key='DEV-BOOK-1'");
        jdbc.update("UPDATE home_shortcut SET label='Operator edited shortcut' WHERE item_key='1'");
        Object seed = context.getBean("developmentContentSeed");
        seed.getClass().getMethod("initialize").invoke(seed);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT label FROM home_shortcut WHERE item_key='1'", String.class))
                .isEqualTo("Operator edited shortcut");
        assertThat(call("GET", "/home", null, null, 200).at("/shortcuts/0/label").asText())
                .isEqualTo("视频课程");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM product WHERE seed_key='DEV-BOOK-1'",
                                String.class))
                .isEqualTo("User edited title");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT on_hand-reserved FROM book_inventory", Integer.class))
                .containsOnly(20);
    }

    @Test
    void seedsTheCurrentMiniProgramDesignAssetsAndBindsThemToContent() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT a.seed_key FROM product p JOIN file_asset a ON"
                                    + " a.id=p.cover_asset_id WHERE p.seed_key='DEV-BOOK-1'",
                                String.class))
                .isEqualTo("DEV-DESIGN-COVER-BOOK");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT a.seed_key FROM product p JOIN file_asset a ON"
                                    + " a.id=p.cover_asset_id WHERE p.seed_key='DEV-COURSE-1'",
                                String.class))
                .isEqualTo("DEV-DESIGN-COVER-COURSE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT a.seed_key FROM paper p JOIN file_asset a ON"
                                    + " a.id=p.cover_asset_id WHERE p.seed_key='DEV-PAPER-1'",
                                String.class))
                .isEqualTo("DEV-DESIGN-COVER-PAPER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sha256 FROM file_asset WHERE seed_key='DEV-DESIGN-COVER-BOOK'",
                                String.class))
                .isEqualTo("f0bb5b45da5a3a7ba6f6d7bfbbf5ace3293f0a656cf1c4d2ca5c1246aa16ce87");
        assertThat(
                        jdbc.queryForList(
                                "SELECT a.seed_key FROM home_banner b JOIN file_asset a ON"
                                    + " a.id=b.asset_id ORDER BY b.sort_no",
                                String.class))
                .containsExactly(
                        "DEV-DESIGN-BANNER-BOOKS",
                        "DEV-DESIGN-BANNER-WRITING",
                        "DEV-DESIGN-BANNER-READING");
    }

    @Test
    void v2UpgradePreservesManualHomeConfigurationAndPublishedSnapshot() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var custom = upload(token, "PUBLIC_IMAGE", "manual-home.png", "image/png", png());
        long customAsset = custom.path("id").asLong();
        long baseRevision =
                jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class);
        int baseLayouts = jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Integer.class);
        long basePublishedRevision =
                jdbc.queryForObject(
                        "SELECT MAX(revision) FROM home_layout WHERE status='PUBLISHED'", Long.class);
        Map<String, Object> banner =
                jdbc.queryForMap(
                        "SELECT title,asset_id,sort_no FROM home_banner WHERE item_key='1'");
        Map<String, Object> shortcut =
                jdbc.queryForMap(
                        "SELECT label,asset_id,sort_no FROM home_shortcut WHERE item_key='1'");

        jdbc.update(
                "INSERT INTO home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version)"
                        + " VALUES('manual-ui','BOOK_GRID','人工装修模块',99,false,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
        jdbc.update(
                "UPDATE home_banner SET title='人工横幅',asset_id=?,sort_no=7 WHERE item_key='1'",
                customAsset);
        jdbc.update(
                "UPDATE home_shortcut SET label='人工入口',asset_id=?,sort_no=7 WHERE item_key='1'",
                customAsset);
        jdbc.update("UPDATE home_draft SET revision=? WHERE id=1", baseRevision + 50);
        jdbc.update("DELETE FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V2'");

        try {
            initializeSeed();

            Map<String, Object> upgradedBanner =
                    jdbc.queryForMap(
                            "SELECT title,asset_id,sort_no FROM home_banner WHERE item_key='1'");
            assertThat(upgradedBanner.get("title")).isEqualTo("人工横幅");
            assertThat(((Number) upgradedBanner.get("asset_id")).longValue()).isEqualTo(customAsset);
            assertThat(((Number) upgradedBanner.get("sort_no")).intValue()).isEqualTo(7);
            Map<String, Object> upgradedShortcut =
                    jdbc.queryForMap(
                            "SELECT label,asset_id,sort_no FROM home_shortcut WHERE item_key='1'");
            assertThat(upgradedShortcut.get("label")).isEqualTo("人工入口");
            assertThat(((Number) upgradedShortcut.get("asset_id")).longValue()).isEqualTo(customAsset);
            assertThat(((Number) upgradedShortcut.get("sort_no")).intValue()).isEqualTo(7);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM home_section WHERE item_key='manual-ui'",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class))
                    .isEqualTo(baseRevision + 50);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Integer.class))
                    .isEqualTo(baseLayouts);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT MAX(revision) FROM home_layout WHERE status='PUBLISHED'",
                                    Long.class))
                    .isEqualTo(basePublishedRevision);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V2'",
                                    Integer.class))
                    .isEqualTo(1);

            initializeSeed();
            assertThat(jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class))
                    .isEqualTo(baseRevision + 50);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Integer.class))
                    .isEqualTo(baseLayouts);
        } finally {
            jdbc.update("DELETE FROM home_section WHERE item_key='manual-ui'");
            jdbc.update(
                    "UPDATE home_banner SET title=?,asset_id=?,sort_no=? WHERE item_key='1'",
                    banner.get("title"),
                    banner.get("asset_id"),
                    banner.get("sort_no"));
            jdbc.update(
                    "UPDATE home_shortcut SET label=?,asset_id=?,sort_no=? WHERE item_key='1'",
                    shortcut.get("label"),
                    shortcut.get("asset_id"),
                    shortcut.get("sort_no"));
            jdbc.update("UPDATE home_draft SET revision=? WHERE id=1", baseRevision);
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V2'",
                            Integer.class)
                    == 0)
                jdbc.update(
                        "INSERT INTO dev_seed_run(seed_key,created_at,updated_at,version)"
                                + " VALUES('DEV-CONTENT-V2',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
        }
    }

    @Test
    void failedLegacySeedRollsBackWithoutMarkingUpgradeComplete() throws Exception {
        String before =
                jdbc.queryForObject(
                        "SELECT label FROM home_shortcut WHERE item_key='1'", String.class);
        long revision =
                jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class);
        jdbc.update("DELETE FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V1'");
        try {
            assertThatThrownBy(this::initializeSeed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Development content seed failed");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V1'",
                                    Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT label FROM home_shortcut WHERE item_key='1'", String.class))
                    .isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class))
                    .isEqualTo(revision);
        } finally {
            jdbc.update(
                    "INSERT INTO dev_seed_run(seed_key,created_at,updated_at,version)"
                            + " VALUES('DEV-CONTENT-V1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
        }
    }

    @Test
    void missingV2MarkerIsRepairedWithoutChangingAnAlreadyUpgradedHome() throws Exception {
        long revision =
                jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class);
        int layouts = jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Integer.class);
        int sections =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM home_section WHERE type IN"
                                + " ('BOOK_GRID','COURSE_GRID','PAPER_GRID')",
                        Integer.class);
        assertThat(sections).isEqualTo(3);
        jdbc.update("DELETE FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V2'");

        initializeSeed();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V2'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class))
                .isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Integer.class))
                .isEqualTo(layouts);
    }

    private void initializeSeed() throws Exception {
        Object seed = context.getBean("developmentContentSeed");
        try {
            seed.getClass().getMethod("initialize").invoke(seed);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }
}
