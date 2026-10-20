package com.wechat.jxsc.common.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.jxsc.catalog.CatalogConsistencyGuard;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Upgrades known original examples, including databases using REAL WeChat identity in dev. */
@Component
@Profile("!prod & (dev | test)")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DevelopmentContentChineseUpgrade implements ApplicationRunner {
    static final String SUBTITLE = "学习内容开发示例，正式内容请以运营发布为准";
    static final String DETAIL = "此为开发示例内容，请由运营替换为已获授权的正式教学内容。";
    static final String SHIPPING = "示例配送说明，正式运费和配送范围待运营配置";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CatalogConsistencyGuard guard;
    private final TransactionTemplate tx;

    public DevelopmentContentChineseUpgrade(
            JdbcTemplate db,
            ObjectMapper json,
            CatalogConsistencyGuard guard,
            PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.guard = guard;
        this.tx = new TransactionTemplate(manager);
    }

    static String categoryName(String type) {
        return switch (type) {
            case "BOOK" -> "语文图书（示例）";
            case "COURSE" -> "语文课程（示例）";
            case "MEMBERSHIP" -> "试卷会员（示例）";
            default -> throw new IllegalArgumentException(type);
        };
    }

    static String productTitle(String type, int number) {
        return switch (type) {
            case "BOOK" -> number == 1 ? "语文阅读练习册（示例）" : "语文写作练习册（示例）";
            case "COURSE" -> number == 1 ? "语文阅读入门课程（示例）" : "语文写作基础课程（示例）";
            case "MEMBERSHIP" -> number == 1 ? "半年试卷会员（示例）" : "全年试卷会员（示例）";
            default -> throw new IllegalArgumentException(type);
        };
    }

    static String userNickname(String fixture) {
        return "开发示例 · "
                + switch (fixture) {
                    case "buyer" -> "普通学员";
                    case "buyer-active-member" -> "有效会员";
                    case "buyer-expired-member" -> "到期会员";
                    case "distributor-a" -> "推广员甲";
                    case "distributor-b" -> "推广员乙";
                    default -> throw new IllegalArgumentException(fixture);
                };
    }

    static String bannerTitle(int number) {
        return switch (number) {
            case 1 -> "语文阅读课程（示例）";
            case 2 -> "语文写作课程（示例）";
            case 3 -> "语文精选图书（示例）";
            default -> throw new IllegalArgumentException();
        };
    }

    static String assetName(String seed, String extension) {
        String name =
                switch (seed) {
                    case "DEV-DESIGN-COVER-BOOK" -> "语文精选图书封面";
                    case "DEV-DESIGN-COVER-COURSE" -> "热门视频课程封面";
                    case "DEV-DESIGN-COVER-PAPER" -> "会员试卷封面";
                    case "DEV-DESIGN-BANNER-BOOKS" -> "图书首页横幅";
                    case "DEV-DESIGN-BANNER-WRITING" -> "课程首页横幅";
                    case "DEV-DESIGN-BANNER-READING" -> "试卷首页横幅";
                    case "DEV-PUBLIC-IMAGE" -> "开发示例封面";
                    default -> "语文练习试卷（示例）";
                };
        return name
                + "."
                + extension.toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments ignored) {
        initialize();
    }

    public void initialize() {
        tx.executeWithoutResult(
                ignored -> {
                    guard.acquire();
                    long draftRevision =
                            db.queryForObject(
                                    "SELECT revision FROM home_draft WHERE id=1 FOR UPDATE",
                                    Long.class);
                    if (db.queryForObject(
                                    "SELECT COUNT(*) FROM dev_seed_run WHERE"
                                            + " seed_key='DEV-CONTENT-V1'",
                                    Integer.class)
                            == 0) return;
                    try {
                        translateRows();
                        translateHome(draftRevision);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(
                                "Chinese development content upgrade failed", e);
                    }
                });
    }

    private void translateRows() throws java.io.IOException {
        for (String type : List.of("BOOK", "COURSE", "MEMBERSHIP")) {
            replace(
                    "category",
                    "name",
                    "seed_key=?",
                    "DEV " + type,
                    categoryName(type),
                    "DEV-CATEGORY-" + type);
            for (int i = 1; i <= 2; i++) {
                String key = "DEV-" + type + "-" + i;
                replace(
                        "product",
                        "title",
                        "seed_key=?",
                        key + " 开发数据",
                        productTitle(type, i),
                        key);
                replace(
                        "product",
                        "subtitle",
                        "seed_key=?",
                        "Self-authored development example",
                        SUBTITLE,
                        key);
                replace(
                        "product",
                        "shipping_summary",
                        "seed_key=?",
                        "DEV shipping rules pending",
                        SHIPPING,
                        key);
                for (var row :
                        db.queryForList(
                                "SELECT id,detail_json FROM product WHERE seed_key=? FOR UPDATE",
                                key)) {
                    var detail = json.readTree(row.get("detail_json").toString());
                    var original =
                            json.valueToTree(
                                    List.of(
                                            new BodyBlocks.Input(
                                                    BodyBlocks.Type.TEXT,
                                                    "DEV example. Replace with your own authorized"
                                                            + " content.",
                                                    null)));
                    if (detail.equals(original)) {
                        db.update(
                                "UPDATE product SET"
                                    + " detail_json=?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                                    + " WHERE id=?",
                                json.writeValueAsString(
                                        List.of(
                                                new BodyBlocks.Input(
                                                        BodyBlocks.Type.TEXT, DETAIL, null))),
                                row.get("id"));
                    }
                }
                if (type.equals("COURSE")) {
                    replace(
                            "course",
                            "teacher_name",
                            "product_id IN (SELECT id FROM product WHERE seed_key=?)",
                            "DEV Teacher",
                            "示例讲师",
                            key);
                    for (Long courseId :
                            db.queryForList(
                                    "SELECT c.id FROM course c JOIN product p ON p.id=c.product_id"
                                            + " WHERE p.seed_key=? FOR UPDATE",
                                    Long.class,
                                    key)) {
                        int changed = 0;
                        for (int chapter = 1; chapter <= 3; chapter++) {
                            changed +=
                                    replace(
                                            "course_chapter",
                                            "title",
                                            "course_id=? AND sort_no=?",
                                            "DEV Chapter " + chapter,
                                            "第" + chapter + "章（示例）",
                                            courseId,
                                            chapter);
                        }
                        if (changed > 0)
                            db.update(
                                    "UPDATE course SET"
                                        + " chapter_revision=chapter_revision+1,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                                        + " WHERE id=?",
                                    courseId);
                    }
                }
            }
        }
        replace(
                "paper",
                "title",
                "seed_key=?",
                "DEV Self-authored practice",
                "九年级语文练习试卷（示例）",
                "DEV-PAPER-1");
        db.queryForList("SELECT id FROM paper WHERE seed_key='DEV-PAPER-1' FOR UPDATE", Long.class);
        int attachmentChanges = 0;
        for (String format : List.of("DOCX", "PDF", "PNG")) {
            attachmentChanges +=
                    replace(
                            "paper_attachment",
                            "title",
                            "paper_id IN (SELECT id FROM paper WHERE seed_key=?) AND asset_id IN"
                                    + " (SELECT id FROM file_asset WHERE seed_key=?)",
                            "DEV practice " + format,
                            "语文练习试卷（示例）· " + format,
                            "DEV-PAPER-1",
                            "DEV-PAPER-" + format);
            replace(
                    "file_asset",
                    "original_name",
                    "seed_key=?",
                    "DEV-PAPER-" + format + "." + format.toLowerCase(Locale.ROOT),
                    assetName("DEV-PAPER-" + format, format),
                    "DEV-PAPER-" + format);
        }
        if (attachmentChanges > 0) {
            db.update(
                    "UPDATE paper SET"
                        + " attachment_revision=attachment_revision+1,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                        + " WHERE seed_key='DEV-PAPER-1'");
        }
        replace("article", "title", "seed_key=?", "DEV Welcome", "课程评价", "DEV-ARTICLE-1");
        for (var row :
                db.queryForList(
                        "SELECT id,body_json FROM article WHERE seed_key='DEV-ARTICLE-1' FOR"
                                + " UPDATE")) {
            var original =
                    json.valueToTree(
                            List.of(
                                    new BodyBlocks.Input(
                                            BodyBlocks.Type.TEXT,
                                            "DEV teaching examples created for this project.",
                                            null)));
            if (json.readTree(row.get("body_json").toString()).equals(original)) {
                db.update(
                        "UPDATE article SET"
                                + " body_json=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE"
                                + " id=?",
                        json.writeValueAsString(
                                List.of(
                                        new BodyBlocks.Input(
                                                BodyBlocks.Type.TEXT,
                                                "力山学堂课程导读。此为开发示例内容，正式课程介绍由运营编辑发布。",
                                                null))),
                        row.get("id"));
            }
        }
        replace(
                "file_asset",
                "original_name",
                "seed_key=?",
                "DEV-PUBLIC-IMAGE.png",
                assetName("DEV-PUBLIC-IMAGE", "PNG"),
                "DEV-PUBLIC-IMAGE");
        for (String fixture :
                List.of(
                        "buyer",
                        "buyer-active-member",
                        "buyer-expired-member",
                        "distributor-a",
                        "distributor-b")) {
            replace(
                    "user_account",
                    "nickname",
                    "id IN (SELECT user_id FROM user_wechat_identity WHERE app_id='DEV' AND"
                            + " openid=?)",
                    "开发数据 · " + fixture,
                    userNickname(fixture),
                    fixture);
        }
    }

    // SQL identifiers/predicates are internal constants; all content/identities are bound values.
    private int replace(
            String table,
            String column,
            String predicate,
            String oldText,
            String text,
            Object... identity) {
        var args = new ArrayList<Object>();
        args.add(text);
        args.addAll(Arrays.asList(identity));
        args.add(oldText);
        return db.update(
                "UPDATE "
                        + table
                        + " SET "
                        + column
                        + "=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE "
                        + predicate
                        + " AND BINARY "
                        + column
                        + "=BINARY ?",
                args.toArray());
    }

    private void translateHome(long draftRevision) throws java.io.IOException {
        var ids = new HashMap<String, String>();
        for (var row :
                db.queryForList(
                        "SELECT id,seed_key FROM product WHERE seed_key IN"
                            + " ('DEV-BOOK-1','DEV-BOOK-2','DEV-COURSE-1','DEV-COURSE-2','DEV-MEMBERSHIP-1','DEV-MEMBERSHIP-2')")) {
            ids.put(row.get("id").toString(), row.get("seed_key").toString());
        }
        int draftChanged = 0;
        for (int i = 1; i <= 8; i++) {
            draftChanged +=
                    replace(
                            "home_shortcut",
                            "label",
                            "item_key=? AND target_type='PRODUCT_LIST' AND target_id IS NULL AND"
                                + " asset_id IN (SELECT id FROM file_asset WHERE"
                                + " seed_key='DEV-PUBLIC-IMAGE') AND JSON_LENGTH(filters_json)=1"
                                + " AND JSON_UNQUOTE(JSON_EXTRACT(filters_json,'$.type'))=?",
                            "DEV Entry " + i,
                            "内容入口" + i + "（示例）",
                            "" + i,
                            i % 2 == 0 ? "COURSE" : "BOOK");
        }
        for (int i = 1; i <= 3; i++) {
            draftChanged +=
                    replace(
                            "home_banner",
                            "title",
                            "item_key=? AND target_type='PRODUCT' AND target_id IN (SELECT id FROM"
                                    + " product WHERE seed_key=?)",
                            "DEV Banner " + i,
                            bannerTitle(i),
                            "" + i,
                            i == 3 ? "DEV-BOOK-1" : "DEV-COURSE-" + i);
        }
        draftChanged +=
                replace(
                        "home_section",
                        "title",
                        "item_key='1' AND type='PRODUCT_GRID' AND id IN (SELECT section_id FROM"
                            + " home_section_item WHERE product_id IN (SELECT id FROM product WHERE"
                            + " seed_key IN ('DEV-BOOK-1','DEV-BOOK-2')))",
                        "DEV Featured books",
                        "精选图书（示例）");
        var rows =
                db.queryForList(
                        "SELECT modules_json FROM home_layout WHERE status='PUBLISHED' ORDER BY"
                                + " revision DESC LIMIT 1",
                        String.class);
        boolean snapshotChanged = false;
        ObjectNode snapshot = null;
        if (!rows.isEmpty()) {
            snapshot = (ObjectNode) json.readTree(rows.getFirst());
            for (var shortcut : snapshot.path("shortcuts")) {
                String id = shortcut.path("id").asText();
                if (!id.matches("[1-8]")) continue;
                var target = shortcut.path("target");
                int number = Integer.parseInt(id);
                if (shortcut.path("label").asText().equals("DEV Entry " + id)
                        && target.path("type").asText().equals("PRODUCT_LIST")
                        && target.path("id").isNull()
                        && target.path("filters").size() == 1
                        && target.path("filters")
                                .path("type")
                                .asText()
                                .equals(number % 2 == 0 ? "COURSE" : "BOOK")) {
                    ((ObjectNode) shortcut).put("label", "内容入口" + id + "（示例）");
                    snapshotChanged = true;
                }
            }
            for (var banner : snapshot.path("banners")) {
                String id = banner.path("id").asText();
                if (List.of("1", "2", "3").contains(id)
                        && banner.path("target").path("type").asText().equals("PRODUCT")
                        && Objects.equals(
                                ids.get(banner.path("target").path("id").asText()),
                                id.equals("3") ? "DEV-BOOK-1" : "DEV-COURSE-" + id)
                        && banner.path("title").asText().equals("DEV Banner " + id)) {
                    ((ObjectNode) banner).put("title", bannerTitle(Integer.parseInt(id)));
                    snapshotChanged = true;
                }
            }
            for (var section : snapshot.path("sections")) {
                var article = section.path("article");
                if (article.isObject()
                        && article.path("title").asText().equals("DEV Welcome")
                        && db.queryForObject(
                                        "SELECT COUNT(*) FROM article WHERE CAST(id AS CHAR)=? AND"
                                                + " seed_key='DEV-ARTICLE-1'",
                                        Integer.class,
                                        article.path("id").asText())
                                > 0) {
                    ((ObjectNode) article).put("title", "课程评价");
                    snapshotChanged = true;
                }
                boolean seededBook = false;
                for (var product : section.path("products")) {
                    String key = ids.get(product.path("id").asText());
                    if (key == null) continue;
                    if (key.startsWith("DEV-BOOK-")) seededBook = true;
                    if (product.path("title").asText().equals(key + " 开发数据")) {
                        String[] parts = key.split("-");
                        ((ObjectNode) product)
                                .put("title", productTitle(parts[1], Integer.parseInt(parts[2])));
                        snapshotChanged = true;
                    }
                    if (product.path("subtitle")
                            .asText()
                            .equals("Self-authored development example")) {
                        ((ObjectNode) product).put("subtitle", SUBTITLE);
                        snapshotChanged = true;
                    }
                }
                if (seededBook
                        && section.path("id").asText().equals("1")
                        && section.path("type").asText().equals("PRODUCT_GRID")
                        && section.path("title").asText().equals("DEV Featured books")) {
                    ((ObjectNode) section).put("title", "精选图书（示例）");
                    snapshotChanged = true;
                }
            }
        }
        if (snapshotChanged) {
            long maxRevision =
                    db.queryForObject(
                            "SELECT COALESCE(MAX(revision),0) FROM home_layout", Long.class);
            long revision = Math.max(draftRevision, maxRevision) + 1;
            snapshot.put("revision", revision);
            db.update(
                    "INSERT INTO"
                        + " home_layout(revision,modules_json,status,published_at,seed_key,created_at,updated_at,version)"
                        + " VALUES(?,?,'PUBLISHED',UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    revision,
                    json.writeValueAsString(snapshot),
                    "DEV-HOME-ZH-" + revision);
            db.update(
                    "UPDATE home_draft SET revision=?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                            + " WHERE id=1",
                    revision);
        } else if (draftChanged > 0) {
            db.update(
                    "UPDATE home_draft SET"
                        + " revision=revision+1,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE"
                        + " id=1");
        }
    }
}
