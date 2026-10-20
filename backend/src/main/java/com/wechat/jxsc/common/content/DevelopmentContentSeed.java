package com.wechat.jxsc.common.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.catalog.CatalogConsistencyGuard;
import com.wechat.jxsc.common.storage.*;
import com.wechat.jxsc.homepage.HomeService;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.*;

@Component
@Conditional(DevelopmentSeedCondition.class)
@Order(0)
public class DevelopmentContentSeed implements ApplicationRunner {
    private final JdbcTemplate db;
    private final StorageProvider storage;
    private final FormatScanner scanner;
    private final HomeService home;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final CatalogConsistencyGuard guard;

    public DevelopmentContentSeed(
            JdbcTemplate db,
            StorageProvider storage,
            FormatScanner scanner,
            HomeService home,
            ObjectMapper json,
            PlatformTransactionManager manager,
            CatalogConsistencyGuard guard) {
        this.db = db;
        this.storage = storage;
        this.scanner = scanner;
        this.home = home;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.guard = guard;
    }

    public void run(ApplicationArguments ignored) {
        initialize();
    }

    public void initialize() {
        tx.executeWithoutResult(
                ignored -> {
                    guard.acquire();
                    db.queryForObject(
                            "SELECT revision FROM home_draft WHERE id=1 FOR UPDATE", Long.class);
                    if (db.queryForObject(
                                    "SELECT COUNT(*) FROM dev_seed_run WHERE"
                                            + " seed_key='DEV-CONTENT-V1'",
                                    Integer.class)
                            > 0) {
                        // A previous process could record V2 before its layout transaction
                        // completed. Retry based on the actual published structure so an old
                        // high revision cannot prevent the upgrade. Also repair a missing V2
                        // marker on databases that already have the upgraded layout.
                        if (db.queryForObject(
                                        "SELECT COUNT(*) FROM dev_seed_run WHERE"
                                                + " seed_key='DEV-CONTENT-V2'",
                                        Integer.class)
                                == 0
                                || needsV2Upgrade()) upgradeV2();
                        return;
                    }
                    try {
                        seed();
                    } catch (Exception e) {
                        throw new IllegalStateException("Development content seed failed", e);
                    }
                    db.update(
                            "INSERT INTO dev_seed_run(seed_key,created_at,updated_at,version)"
                                + " VALUES('DEV-CONTENT-V1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
                    db.update(
                            "INSERT INTO dev_seed_run(seed_key,created_at,updated_at,version)"
                                + " VALUES('DEV-CONTENT-V2',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
                });
    }

    /** Upgrades a database that already received the original eight-entry development fixture. */
    private void upgradeV2() {
        db.update(
                "UPDATE course c JOIN product p ON p.id=c.product_id SET c.duration_minutes=6"
                    + " WHERE p.seed_key LIKE 'DEV-COURSE-%' AND c.duration_minutes=0");
        db.update("UPDATE paper SET school_stage=stage WHERE school_stage IS NULL OR school_stage=''");
        db.update("UPDATE paper SET paper_type='PRACTICE' WHERE paper_type IS NULL OR paper_type=''");
        db.update("UPDATE paper SET summary='开发示例试卷' WHERE seed_key='DEV-PAPER-1' AND summary=''");

        long draftRevision =
                db.queryForObject("SELECT revision FROM home_draft WHERE id=1 FOR UPDATE", Long.class);
        if (canReplaceUntouchedLegacyHome()) {
            Long cover =
                    db.queryForObject(
                            "SELECT id FROM file_asset WHERE seed_key='DEV-PUBLIC-IMAGE'",
                            Long.class);
            List<Long> books =
                    db.queryForList(
                            "SELECT id FROM product WHERE seed_key LIKE 'DEV-BOOK-%' AND status='ON_SALE' ORDER BY id",
                            Long.class);
            List<Long> courses =
                    db.queryForList(
                            "SELECT id FROM product WHERE seed_key LIKE 'DEV-COURSE-%' AND status='ON_SALE' ORDER BY id",
                            Long.class);
            Long paper =
                    db.queryForObject(
                            "SELECT id FROM paper WHERE seed_key='DEV-PAPER-1'", Long.class);
            db.update("DELETE FROM home_section_paper");
            db.update("DELETE FROM home_section_item");
            db.update("DELETE FROM home_section");
            db.update("DELETE FROM home_banner");
            db.update("DELETE FROM home_shortcut");
            for (int i = 1; i <= 3; i++)
                db.update(
                        "INSERT INTO home_banner(item_key,title,asset_id,target_type,target_id,filters_json,sort_no,enabled,created_at,updated_at,version)"
                            + " VALUES(?,?,?,'PRODUCT',?,'{}',?,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        "" + i,
                        DevelopmentContentChineseUpgrade.bannerTitle(i),
                        cover,
                        i == 3 ? books.getFirst() : courses.get(i - 1),
                        i);
            var labels = List.of("视频课程", "作文书籍", "会员试卷", "我的学习", "会员中心", "订单物流");
            var targetTypes = List.of("PRODUCT_LIST", "PRODUCT_LIST", "PAPER_LIST", "LEARNING", "MEMBERSHIP_CENTER", "ORDER_LIST");
            for (int i = 1; i <= 6; i++)
                db.update(
                        "INSERT INTO home_shortcut(item_key,label,asset_id,target_type,target_id,filters_json,sort_no,enabled,created_at,updated_at,version)"
                            + " VALUES(?,?,?,?,?,?,?,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        "" + i,
                        labels.get(i - 1),
                        cover,
                        targetTypes.get(i - 1),
                        null,
                        writeJson(
                                i == 1
                                        ? Map.of("type", "COURSE")
                                        : i == 2
                                                ? Map.of("type", "BOOK")
                                                : i == 3
                                                        ? Map.of("paperType", "PRACTICE")
                                                        : Map.of()),
                        i);
            long bookSection =
                    insert(
                            "INSERT INTO home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version) VALUES('books','BOOK_GRID','语文精选图书',0,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
            insertSectionItems(bookSection, books);
            long courseSection =
                    insert(
                            "INSERT INTO home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version) VALUES('courses','COURSE_GRID','热门视频课程',1,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
            insertSectionItems(courseSection, courses);
            long paperSection =
                    insert(
                            "INSERT INTO home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version) VALUES('papers','PAPER_GRID','会员试卷下载',2,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
            db.update(
                    "INSERT INTO home_section_paper(section_id,paper_id,sort_no,created_at,updated_at,version) VALUES(?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    paperSection,
                    paper);
            long revision = draftRevision + 1;
            db.update("UPDATE home_draft SET revision=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=1", revision);
            db.update(
                    "INSERT INTO home_layout(revision,modules_json,status,published_at,published_by,seed_key,created_at,updated_at,version) VALUES(?,?,'PUBLISHED',UTC_TIMESTAMP(6),NULL,'DEV-HOME-2',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    revision,
                    writeJson(home.preview()));
        }
        db.update(
                "INSERT INTO dev_seed_run(seed_key,created_at,updated_at,version)"
                        + " SELECT 'DEV-CONTENT-V2',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0"
                        + " WHERE NOT EXISTS (SELECT 1 FROM dev_seed_run WHERE"
                        + " seed_key='DEV-CONTENT-V2')");
    }

    private boolean needsV2Upgrade() {
        return db.queryForObject(
                        "SELECT COUNT(*) FROM home_section WHERE type IN"
                                + " ('BOOK_GRID','COURSE_GRID','PAPER_GRID')",
                        Integer.class)
                != 3;
    }

    /**
     * V1 predates the three-business home contract. Only its untouched, self-authored fixture may
     * be replaced; any operator edit, custom asset, extra module, or real publication is preserved.
     */
    private boolean canReplaceUntouchedLegacyHome() {
        Long revision =
                db.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class);
        if (revision == null || revision != 1) return false;
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM home_layout WHERE status='PUBLISHED'"
                                + " AND seed_key='DEV-HOME-1' AND published_by IS NULL",
                        Integer.class)
                != 1) return false;
        if (db.queryForObject("SELECT COUNT(*) FROM home_banner", Integer.class) != 3
                || db.queryForObject("SELECT COUNT(*) FROM home_shortcut", Integer.class) != 8
                || db.queryForObject("SELECT COUNT(*) FROM home_section", Integer.class) != 1
                || db.queryForObject("SELECT COUNT(*) FROM home_section_paper", Integer.class) != 0)
            return false;
        List<Long> covers =
                db.queryForList(
                        "SELECT id FROM file_asset WHERE seed_key='DEV-PUBLIC-IMAGE'", Long.class);
        if (covers.size() != 1) return false;
        long cover = covers.getFirst();
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM home_banner WHERE asset_id<>?", Integer.class, cover)
                != 0
                || db.queryForObject(
                                "SELECT COUNT(*) FROM home_shortcut WHERE asset_id<>?",
                                Integer.class,
                                cover)
                        != 0)
            return false;
        List<String> bannerKeys =
                db.queryForList(
                        "SELECT item_key FROM home_banner ORDER BY CAST(item_key AS UNSIGNED),item_key",
                        String.class);
        List<String> shortcutKeys =
                db.queryForList(
                        "SELECT item_key FROM home_shortcut ORDER BY CAST(item_key AS UNSIGNED),item_key",
                        String.class);
        if (!bannerKeys.equals(List.of("1", "2", "3"))
                || !shortcutKeys.equals(List.of("1", "2", "3", "4", "5", "6", "7", "8")))
            return false;
        return db.queryForObject(
                        "SELECT COUNT(*) FROM home_section WHERE item_key='1' AND type='PRODUCT_GRID'",
                        Integer.class)
                == 1;
    }

    private void insertSectionItems(long section, List<Long> products) {
        for (int i = 0; i < products.size(); i++)
            db.update(
                    "INSERT INTO home_section_item(section_id,product_id,sort_no,created_at,updated_at,version) VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    section,
                    products.get(i),
                    i);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Development seed JSON failed", e);
        }
    }

    private void seed() throws Exception {
        for (String fixture :
                List.of(
                        "buyer",
                        "buyer-active-member",
                        "buyer-expired-member",
                        "distributor-a",
                        "distributor-b")) {
            if (db.queryForObject(
                            "SELECT COUNT(*) FROM user_wechat_identity WHERE app_id='DEV' AND"
                                    + " openid=?",
                            Integer.class,
                            fixture)
                    == 0) {
                long user =
                        insert(
                                "INSERT INTO"
                                    + " user_account(nickname,status,created_at,updated_at,version)"
                                    + " VALUES(?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                                DevelopmentContentChineseUpgrade.userNickname(fixture));
                db.update(
                        "INSERT INTO"
                            + " user_wechat_identity(user_id,app_id,openid,last_login_at,created_at,updated_at,version)"
                            + " VALUES(?,'DEV',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        user,
                        fixture);
            }
        }
        long bookCover =
                asset(
                        "DEV-DESIGN-COVER-BOOK",
                        "PNG",
                        "image/png",
                        "PUBLIC_IMAGE",
                        DevelopmentDesignAssets.bookCover());
        long courseCover =
                asset(
                        "DEV-DESIGN-COVER-COURSE",
                        "PNG",
                        "image/png",
                        "PUBLIC_IMAGE",
                        DevelopmentDesignAssets.courseCover());
        long paperCover =
                asset(
                        "DEV-DESIGN-COVER-PAPER",
                        "PNG",
                        "image/png",
                        "PUBLIC_IMAGE",
                        DevelopmentDesignAssets.paperCover());
        long bannerBooks =
                asset(
                        "DEV-DESIGN-BANNER-BOOKS",
                        "PNG",
                        "image/png",
                        "PUBLIC_IMAGE",
                        DevelopmentDesignAssets.banner("books"));
        long bannerWriting =
                asset(
                        "DEV-DESIGN-BANNER-WRITING",
                        "PNG",
                        "image/png",
                        "PUBLIC_IMAGE",
                        DevelopmentDesignAssets.banner("writing"));
        long bannerReading =
                asset(
                        "DEV-DESIGN-BANNER-READING",
                        "PNG",
                        "image/png",
                        "PUBLIC_IMAGE",
                        DevelopmentDesignAssets.banner("reading"));
        List<Long> books = new ArrayList<>();
        List<Long> courses = new ArrayList<>();
        for (String type : List.of("BOOK", "COURSE", "MEMBERSHIP")) {
            long category =
                    insert(
                            "INSERT INTO"
                                + " category(name,type,sort_no,enabled,seed_key,created_at,updated_at,version)"
                                + " VALUES(?,?,0,true,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                            DevelopmentContentChineseUpgrade.categoryName(type),
                            type,
                            "DEV-CATEGORY-" + type);
            for (int i = 1; i <= 2; i++) {
                String key = "DEV-" + type + "-" + i;
                boolean book = type.equals("BOOK");
                long product =
                        insert(
                                "INSERT INTO"
                                    + " product(type,title,subtitle,cover_asset_id,price_fen,status,detail_json,sort_no,quantity_limit,shipping_summary,seed_key,created_at,updated_at,version)"
                                    + " VALUES(?,?,?,?,?,'ON_SALE',?,0,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                                type,
                                DevelopmentContentChineseUpgrade.productTitle(type, i),
                                DevelopmentContentChineseUpgrade.SUBTITLE,
                                book ? bookCover : type.equals("COURSE") ? courseCover : bookCover,
                                book
                                        ? 1900L + i * 100
                                        : type.equals("COURSE") ? 3900L + i * 100 : 6000L * i,
                                json.writeValueAsString(
                                        List.of(
                                                new BodyBlocks.Input(
                                                        BodyBlocks.Type.TEXT,
                                                        DevelopmentContentChineseUpgrade.DETAIL,
                                                        null))),
                                book ? 9 : null,
                                book ? DevelopmentContentChineseUpgrade.SHIPPING : null,
                                key);
                db.update(
                        "INSERT INTO product_category(product_id,category_id) VALUES(?,?)",
                        product,
                        category);
                if (book) {
                    books.add(product);
                    db.update(
                            "INSERT INTO"
                                + " book_inventory(product_id,on_hand,reserved,created_at,updated_at,version)"
                                + " VALUES(?,20,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                            product);
                    db.update(
                            "INSERT INTO"
                                + " inventory_ledger(product_id,event_type,on_hand_delta,reserved_delta,on_hand_before,on_hand_after,reserved_before,reserved_after,business_key,reason,created_at,updated_at,version)"
                                + " VALUES(?,'INITIAL',20,0,0,20,0,0,?,'DEV initial"
                                + " inventory',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                            product,
                            "INITIAL:" + product);
                } else if (type.equals("COURSE")) {
                    courses.add(product);
                    long course =
                            insert(
                                    "INSERT INTO"
                                    + " course(product_id,validity_type,validity_days,duration_minutes,teacher_name,chapter_revision,created_at,updated_at,version)"
                                    + " VALUES(?,'DAYS',30,6,'示例讲师',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                                    product);
                    db.update("INSERT INTO entitlement_course_target(course_id) VALUES(?)", course);
                    for (int chapter = 1; chapter <= 3; chapter++)
                        db.update(
                                "INSERT INTO"
                                    + " course_chapter(course_id,title,sort_no,duration_seconds,is_trial,enabled,created_at,updated_at,version)"
                                    + " VALUES(?,?,?,120,?,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                                course,
                                "第" + chapter + "章（示例）",
                                chapter,
                                chapter == 1);
                } else {
                    int months = i * 6;
                    String status =
                            db.queryForObject(
                                                    "SELECT COUNT(*) FROM membership_plan WHERE"
                                                        + " duration_months=? AND status='ENABLED'",
                                                    Integer.class,
                                                    months)
                                            == 0
                                    ? "ENABLED"
                                    : "DISABLED";
                    db.update(
                            "INSERT INTO"
                                + " membership_plan(product_id,level_code,duration_months,status,created_at,updated_at,version)"
                                + " VALUES(?,'STANDARD',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                            product,
                            months,
                            status);
                    if (status.equals("DISABLED"))
                        db.update("UPDATE product SET status='DRAFT' WHERE id=?", product);
                }
            }
        }
        long paper =
                insert(
                        "INSERT INTO"
                            + " paper(title,stage,school_stage,grade,subject,paper_type,year,status,visibility,cover_asset_id,description_json,summary,seed_key,created_at,updated_at,version)"
                            + " VALUES('九年级语文练习试卷（示例）','JUNIOR','JUNIOR','9','CHINESE','PRACTICE',2026,'PUBLISHED','MEMBER',?,'[]','开发示例试卷','DEV-PAPER-1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        paperCover);
        var formats = List.of("DOCX", "PDF", "PNG");
        var mime =
                List.of(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/pdf",
                        "image/png");
        var bytes =
                List.of(
                        AuthoredDevelopmentFiles.docx(),
                        AuthoredDevelopmentFiles.pdf(),
                        AuthoredDevelopmentFiles.png());
        for (int i = 0; i < formats.size(); i++) {
            long asset =
                    asset(
                            "DEV-PAPER-" + formats.get(i),
                            formats.get(i),
                            mime.get(i),
                            "PAPER_ATTACHMENT",
                            bytes.get(i));
            db.update(
                    "INSERT INTO"
                        + " paper_attachment(paper_id,asset_id,title,sort_no,created_at,updated_at,version)"
                        + " VALUES(?,?,?, ?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    paper,
                    asset,
                    "语文练习试卷（示例）· " + formats.get(i),
                    i);
        }
        long guide =
                insert(
                        "INSERT INTO"
                            + " article(title,body_json,status,published_at,seed_key,created_at,updated_at,version)"
                            + " VALUES('课程评价',?,'PUBLISHED',UTC_TIMESTAMP(6),'DEV-ARTICLE-1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        json.writeValueAsString(
                                List.of(
                                        new BodyBlocks.Input(
                                                BodyBlocks.Type.TEXT,
                                                "力山学堂课程导读。此为开发示例内容，正式课程介绍由运营编辑发布。",
                                                null))));
        if (db.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class) == 0) {
            for (int i = 1; i <= 3; i++)
                db.update(
                        "INSERT INTO"
                            + " home_banner(item_key,title,asset_id,target_type,target_id,filters_json,sort_no,enabled,created_at,updated_at,version)"
                            + " VALUES(?,?,?,'PRODUCT',?,'{}',?,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        "" + i,
                        DevelopmentContentChineseUpgrade.bannerTitle(i),
                        i == 1 ? bannerBooks : i == 2 ? bannerWriting : bannerReading,
                        i == 3 ? books.getFirst() : courses.get(i - 1),
                        i);
            var labels = List.of("视频课程", "作文书籍", "会员试卷", "我的学习", "会员中心", "订单物流");
            var targetTypes = List.of("PRODUCT_LIST", "PRODUCT_LIST", "PAPER_LIST", "LEARNING", "MEMBERSHIP_CENTER", "ORDER_LIST");
            for (int i = 1; i <= 6; i++)
                db.update(
                        "INSERT INTO"
                            + " home_shortcut(item_key,label,asset_id,target_type,target_id,filters_json,sort_no,enabled,created_at,updated_at,version)"
                            + " VALUES(?,?,?,?,?,?, ?,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        "" + i,
                        labels.get(i - 1),
                        bookCover,
                        targetTypes.get(i - 1),
                        null,
                        json.writeValueAsString(
                                i == 1
                                        ? Map.of("type", "COURSE")
                                        : i == 2
                                                ? Map.of("type", "BOOK")
                                                : i == 3
                                                        ? Map.of("paperType", "PRACTICE")
                                                        : Map.of()),
                        i);
            long section =
                    insert(
                            "INSERT INTO"
                                + " home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version)"
                            + " VALUES('books','BOOK_GRID','语文精选图书',0,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
            for (int i = 0; i < books.size(); i++)
                db.update(
                        "INSERT INTO"
                            + " home_section_item(section_id,product_id,sort_no,created_at,updated_at,version)"
                            + " VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        section,
                        books.get(i),
                        i);
            long courseSection =
                    insert(
                            "INSERT INTO"
                                + " home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version)"
                                + " VALUES('courses','COURSE_GRID','热门视频课程',1,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
            for (int i = 0; i < courses.size(); i++)
                db.update(
                        "INSERT INTO"
                            + " home_section_item(section_id,product_id,sort_no,created_at,updated_at,version)"
                            + " VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        courseSection,
                        courses.get(i),
                        i);
            long paperSection =
                    insert(
                            "INSERT INTO"
                                + " home_section(item_key,type,title,sort_no,enabled,created_at,updated_at,version)"
                                + " VALUES('papers','PAPER_GRID','会员试卷下载',2,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)");
            db.update(
                    "INSERT INTO"
                        + " home_section_paper(section_id,paper_id,sort_no,created_at,updated_at,version)"
                        + " VALUES(?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    paperSection,
                    paper);
            db.update(
                    "UPDATE home_draft SET revision=1,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                            + " WHERE id=1");
            db.update(
                    "INSERT INTO"
                        + " home_layout(revision,modules_json,status,published_at,published_by,seed_key,created_at,updated_at,version)"
                        + " VALUES(1,?,'PUBLISHED',UTC_TIMESTAMP(6),NULL,'DEV-HOME-1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    json.writeValueAsString(home.preview()));
        }
    }

    private long asset(String seed, String extension, String mime, String purpose, byte[] bytes)
            throws Exception {
        String key =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("MD5")
                                        .digest(
                                                seed.getBytes(
                                                        java.nio.charset.StandardCharsets
                                                                .US_ASCII)));
        StorageProvider.SealedObject object;
        try {
            object = storage.seal(key);
        } catch (com.wechat.jxsc.common.ApiException absent) {
            if (!absent.getCode().equals("FILE_NOT_READY")) throw absent;
            storage.writeOnce(key, new ByteArrayInputStream(bytes), bytes.length);
            object = storage.seal(key);
        }
        byte[] stored;
        try (var in = storage.open(object)) {
            stored = in.readNBytes(bytes.length + 1);
        }
        if (!Arrays.equals(bytes, stored)
                || !scanner.accepts(extension, new ByteArrayInputStream(stored)))
            throw new IOException("Seed format or bytes differ");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored));
        return insert(
                "INSERT INTO"
                    + " file_asset(owner_type,owner_id,purpose,storage_key,sealed_version,original_name,extension,mime,expected_bytes,bytes,sha256,status,scan_status,upload_token_hash,upload_expires_at,seed_key,created_at,updated_at,version)"
                    + " VALUES('SYSTEM',0,?,?,?,?,?,?,?,"
                    + " ?,?,'READY',?,'NO_UPLOAD_CREDENTIAL',UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                purpose,
                key,
                object.version(),
                DevelopmentContentChineseUpgrade.assetName(seed, extension),
                extension,
                mime,
                bytes.length,
                bytes.length,
                hash,
                scanner.status(),
                seed);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        db.update(
                c -> {
                    var ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
                    return ps;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
}
