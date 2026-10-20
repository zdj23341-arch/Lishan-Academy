package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.jxsc.homepage.HomeService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "jxsc.dev-seed.enabled=true")
@Transactional
class ChineseContentIntegrationTest extends ContentHttpSupport {
    @Autowired ApplicationContext context;
    @Autowired HomeService home;

    private void upgrade() throws Exception {
        Object runner = context.getBean("developmentContentChineseUpgrade");
        runner.getClass().getMethod("initialize").invoke(runner);
    }

    @Test
    void freshPublicContentIsChinese() throws Exception {
        String id =
                jdbc.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM product WHERE seed_key='DEV-COURSE-1'",
                        String.class);
        var product = call("GET", "/products/" + id, null, null, 200);
        assertThat(product.path("title").asText()).isEqualTo("语文阅读入门课程（示例）");
        assertThat(product.path("subtitle").asText()).contains("示例").doesNotContain("development");
        assertThat(product.toString()).doesNotContain("DEV Chapter", "DEV Teacher", "DEV example");
        assertThat(call("GET", "/home", null, null, 200).toString())
                .doesNotContain("DEV Banner", "DEV Featured", "Self-authored");
    }

    @Test
    void upgradesLegacyContentAndCopiesOnlyPublishedSnapshotIdempotently() throws Exception {
        jdbc.update(
                "UPDATE product SET title='DEV-BOOK-1 开发数据',subtitle='Self-authored development"
                    + " example',detail_json='[{\"type\":\"TEXT\",\"text\":\"DEV example. Replace"
                    + " with your own authorized"
                    + " content.\",\"assetId\":null}]',shipping_summary='DEV shipping rules"
                    + " pending' WHERE seed_key='DEV-BOOK-1'");
        jdbc.update("UPDATE home_banner SET title='DEV Banner 1' WHERE item_key='1'");
        jdbc.update("UPDATE home_section SET title='DEV Featured books' WHERE item_key='1'");
        long revision =
                jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class) + 1;
        ObjectNode snapshot = json.valueToTree(home.preview());
        snapshot.put("revision", revision);
        String original = json.writeValueAsString(snapshot);
        jdbc.update(
                "INSERT INTO"
                    + " home_layout(revision,modules_json,status,published_at,seed_key,created_at,updated_at,version)"
                    + " VALUES(?,?,'PUBLISHED',UTC_TIMESTAMP(6),'TEST-LEGACY-ZH',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                revision,
                original);
        String storedOriginal =
                jdbc.queryForObject(
                        "SELECT modules_json FROM home_layout WHERE revision=?",
                        String.class,
                        revision);
        jdbc.update("UPDATE home_draft SET revision=? WHERE id=1", revision + 3);
        jdbc.update("UPDATE home_banner SET title='运营未发布的新活动' WHERE item_key='1'");
        var economic =
                jdbc.queryForMap(
                        "SELECT price_fen,status,cover_asset_id FROM product WHERE"
                                + " seed_key='DEV-BOOK-1'");
        var stock = jdbc.queryForList("SELECT * FROM book_inventory");
        long users = jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Long.class);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Long.class);
        upgrade();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM product WHERE seed_key='DEV-BOOK-1'",
                                String.class))
                .isEqualTo("语文阅读练习册（示例）");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT detail_json FROM product WHERE seed_key='DEV-BOOK-1'",
                                String.class))
                .contains("开发示例")
                .doesNotContain("DEV example");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT price_fen,status,cover_asset_id FROM product WHERE"
                                        + " seed_key='DEV-BOOK-1'"))
                .isEqualTo(economic);
        assertThat(jdbc.queryForList("SELECT * FROM book_inventory")).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Long.class))
                .isEqualTo(users);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT modules_json FROM home_layout WHERE revision=?",
                                String.class,
                                revision))
                .isEqualTo(storedOriginal);
        assertThat(home.published().revision()).isEqualTo(revision + 4);
        assertThat(json.writeValueAsString(home.published()))
                .contains("精选图书（示例）", "语文阅读练习册（示例）")
                .doesNotContain("DEV Banner", "Self-authored", "运营未发布的新活动");
        assertThat(home.draft().banners().getFirst().title()).isEqualTo("运营未发布的新活动");
        long version =
                jdbc.queryForObject(
                        "SELECT version FROM product WHERE seed_key='DEV-BOOK-1'", Long.class);
        upgrade();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_layout", Long.class))
                .isEqualTo(count + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM product WHERE seed_key='DEV-BOOK-1'",
                                Long.class))
                .isEqualTo(version);
    }

    @Test
    void preservesOperatorEditsAndDoesNotTouchUnseededProducts() throws Exception {
        jdbc.update(
                "UPDATE product SET title='Operator title',subtitle='Operator"
                        + " subtitle',detail_json='[{\"type\":\"TEXT\",\"text\":\"Operator"
                        + " text\",\"assetId\":null}]' WHERE seed_key='DEV-COURSE-1'");
        jdbc.update(
                "UPDATE product SET seed_key=NULL,title='DEV-COURSE-2 开发数据',subtitle='Self-authored"
                        + " development example' WHERE seed_key='DEV-COURSE-2'");
        var before = jdbc.queryForList("SELECT * FROM product WHERE type='COURSE'");
        upgrade();
        assertThat(jdbc.queryForList("SELECT * FROM product WHERE type='COURSE'"))
                .isEqualTo(before);
    }

    @Test
    void alsoUpgradesOriginalGuideAndEntryLabelsWithoutChangingTheirLinks() throws Exception {
        jdbc.update(
                "UPDATE article SET title='DEV"
                    + " Welcome',body_json='[{\"type\":\"TEXT\",\"text\":\"DEV teaching examples"
                    + " created for this project.\",\"assetId\":null}]' WHERE"
                    + " seed_key='DEV-ARTICLE-1'");
        jdbc.update(
                "UPDATE home_shortcut SET label='DEV Entry"
                    + " 1',target_type='PRODUCT_LIST',target_id=NULL,filters_json='{\"type\":\"BOOK\"}'"
                    + " WHERE item_key='1'");
        long revision =
                jdbc.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class) + 1;
        ObjectNode snapshot = json.valueToTree(home.preview());
        snapshot.put("revision", revision);
        jdbc.update(
                "INSERT INTO"
                    + " home_layout(revision,modules_json,status,published_at,seed_key,created_at,updated_at,version)"
                    + " VALUES(?,?,'PUBLISHED',UTC_TIMESTAMP(6),'TEST-LEGACY-ENTRY',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                revision,
                json.writeValueAsString(snapshot));
        jdbc.update("UPDATE home_draft SET revision=? WHERE id=1", revision);
        var target = home.draft().shortcuts().getFirst().target();
        upgrade();
        assertThat(home.draft().shortcuts().getFirst().target()).isEqualTo(target);
        assertThat(home.draft().shortcuts().getFirst().label()).isEqualTo("内容入口1（示例）");
        assertThat(home.published().shortcuts().getFirst().label()).isEqualTo("内容入口1（示例）");
        String article =
                jdbc.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM article WHERE seed_key='DEV-ARTICLE-1'",
                        String.class);
        var publicArticle = call("GET", "/articles/" + article, null, null, 200);
        assertThat(publicArticle.path("title").asText()).isEqualTo("课程评价");
        assertThat(publicArticle.toString()).contains("开发示例").doesNotContain("DEV teaching");
    }

    @Test
    void withoutSeedMarkerDoesNotTranslateAnyBusinessData() throws Exception {
        jdbc.update("DELETE FROM dev_seed_run WHERE seed_key='DEV-CONTENT-V1'");
        jdbc.update(
                "UPDATE product SET subtitle='Self-authored development example' WHERE"
                        + " seed_key='DEV-BOOK-1'");
        var before = jdbc.queryForList("SELECT * FROM product");
        upgrade();
        assertThat(jdbc.queryForList("SELECT * FROM product")).isEqualTo(before);
    }

    @Test
    void changingAttachmentLabelsInvalidatesOldPaperEditorVersion() throws Exception {
        String token = admin("CONTENT_EDITOR");
        String id =
                jdbc.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM paper WHERE seed_key='DEV-PAPER-1'",
                        String.class);
        jdbc.update("UPDATE paper SET title='运营编辑的试卷' WHERE id=?", id);
        jdbc.update(
                "UPDATE paper_attachment SET title='DEV practice PDF' WHERE paper_id=? AND asset_id"
                        + " IN (SELECT id FROM file_asset WHERE seed_key='DEV-PAPER-PDF')",
                id);
        long version = jdbc.queryForObject("SELECT version FROM paper WHERE id=?", Long.class, id);
        long revision =
                jdbc.queryForObject(
                        "SELECT attachment_revision FROM paper WHERE id=?", Long.class, id);
        ObjectNode editor = (ObjectNode) call("GET", "/admin/papers/" + id, token, null, 200);
        upgrade();
        assertThat(jdbc.queryForObject("SELECT version FROM paper WHERE id=?", Long.class, id))
                .isGreaterThan(version);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attachment_revision FROM paper WHERE id=?", Long.class, id))
                .isEqualTo(revision + 1);
        editor.remove("id");
        var response = call("PATCH", "/admin/papers/" + id, token, editor, 409);
        assertThat(response.path("code").asText()).isEqualTo("VERSION_CONFLICT");
    }
}
