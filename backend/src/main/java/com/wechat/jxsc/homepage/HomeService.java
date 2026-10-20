package com.wechat.jxsc.homepage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.article.*;
import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.catalog.*;
import com.wechat.jxsc.common.content.ContentSupport;
import com.wechat.jxsc.common.storage.AssetService;
import com.wechat.jxsc.paper.PaperDtos;
import com.wechat.jxsc.paper.PaperService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.util.*;

@Service
public class HomeService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetService assets;
    private final ProductService products;
    private final PaperService papers;
    private final ArticleService articles;
    private final HomeTargetValidator targets;
    private final ContentSupport support;

    public HomeService(
            JdbcTemplate db,
            ObjectMapper json,
            AssetService assets,
            ProductService products,
            PaperService papers,
            ArticleService articles,
            HomeTargetValidator targets,
            ContentSupport support) {
        this.db = db;
        this.json = json;
        this.assets = assets;
        this.products = products;
        this.papers = papers;
        this.articles = articles;
        this.targets = targets;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public HomeDtos.Draft draft() {
        long revision = db.queryForObject("SELECT revision FROM home_draft WHERE id=1", Long.class);
        var banners =
                db.query(
                        "SELECT * FROM home_banner ORDER BY sort_no,id",
                        (r, n) ->
                                new HomeDtos.BannerInput(
                                        r.getString("item_key"),
                                        r.getString("title"),
                                        r.getString("asset_id"),
                                        r.getInt("sort_no"),
                                        r.getBoolean("enabled"),
                                        target(r)));
        var shortcuts =
                db.query(
                        "SELECT * FROM home_shortcut ORDER BY sort_no,id",
                        (r, n) ->
                                new HomeDtos.ShortcutInput(
                                        r.getString("item_key"),
                                        r.getString("label"),
                                        r.getString("asset_id"),
                                        r.getInt("sort_no"),
                                        r.getBoolean("enabled"),
                                        target(r)));
        var sections =
                db.query(
                        "SELECT * FROM home_section ORDER BY sort_no,id",
                        (r, n) ->
                                new HomeDtos.SectionInput(
                                        r.getString("item_key"),
                                        HomeDtos.SectionType.valueOf(r.getString("type")),
                                        r.getString("title"),
                                        r.getInt("sort_no"),
                                        r.getBoolean("enabled"),
                                        r.getString("more_target_json") == null
                                                ? null
                                                : read(
                                                        r.getString("more_target_json"),
                                                        HomeDtos.Target.class),
                                        db.queryForList(
                                                "SELECT CAST(product_id AS CHAR) FROM"
                                                    + " home_section_item WHERE section_id=? ORDER"
                                                    + " BY sort_no,id",
                                                String.class,
                                                r.getLong("id")),
                                        r.getString("article_id"),
                                        db.queryForList(
                                                "SELECT CAST(paper_id AS CHAR) FROM"
                                                    + " home_section_paper WHERE section_id=? ORDER"
                                                    + " BY sort_no,id",
                                                String.class,
                                                r.getLong("id")),
                                        HomeDtos.SelectionMode.valueOf(r.getString("selection_mode")),
                                        r.getInt("display_limit"),
                                        readIds(r.getString("pinned_ids"))));
        return new HomeDtos.Draft(revision, banners, shortcuts, sections);
    }

    @Transactional
    public HomeDtos.Draft save(HomeDtos.Draft r) {
        uniqueKeys(r.banners().stream().map(HomeDtos.BannerInput::id).toList());
        uniqueKeys(r.shortcuts().stream().map(HomeDtos.ShortcutInput::id).toList());
        uniqueKeys(r.sections().stream().map(HomeDtos.SectionInput::id).toList());
        for (var b : r.banners()) {
            assets.requirePurpose(ContentSupport.id(b.assetId()), "PUBLIC_IMAGE", true);
            targets.validate(b.target(), false);
        }
        for (var s : r.shortcuts()) {
            assets.requirePurpose(ContentSupport.id(s.assetId()), "PUBLIC_IMAGE", true);
            targets.validate(s.target(), false);
        }
        for (var s : r.sections()) {
            validateSelection(s);
            if (s.moreTarget() != null) targets.validate(s.moreTarget(), false);
            uniqueKeys(s.productIds());
            var paperIds = s.paperIds() == null ? List.<String>of() : s.paperIds();
            uniqueKeys(paperIds);
            boolean productSection =
                    s.type() == HomeDtos.SectionType.PRODUCT_GRID
                            || s.type() == HomeDtos.SectionType.BOOK_GRID
                            || s.type() == HomeDtos.SectionType.COURSE_GRID;
            if (!productSection && !s.productIds().isEmpty()
                    || s.type() != HomeDtos.SectionType.PAPER_GRID && !paperIds.isEmpty()
                    || s.type() != HomeDtos.SectionType.ARTICLE_CARD && s.articleId() != null)
                throw ContentSupport.invalid("Section payload must match its type");
            for (String id : s.productIds()) {
                var product = products.getAdmin(ContentSupport.id(id));
                if (s.type() == HomeDtos.SectionType.BOOK_GRID
                        && product.type() != Product.Type.BOOK)
                    throw ContentSupport.invalid("Book section requires BOOK products");
                if (s.type() == HomeDtos.SectionType.COURSE_GRID
                        && product.type() != Product.Type.COURSE)
                    throw ContentSupport.invalid("Course section requires COURSE products");
            }
            for (String id : paperIds) papers.adminGet(ContentSupport.id(id));
            if (s.articleId() != null) articles.adminGet(ContentSupport.id(s.articleId()));
        }
        if (db.update(
                        "UPDATE home_draft SET"
                            + " revision=revision+1,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                            + " WHERE id=1 AND revision=?",
                        r.revision())
                != 1) throw ContentSupport.stale();
        db.update("DELETE FROM home_section_item");
        db.update("DELETE FROM home_section_paper");
        db.update("DELETE FROM home_section");
        db.update("DELETE FROM home_banner");
        db.update("DELETE FROM home_shortcut");
        for (var b : r.banners())
            db.update(
                    "INSERT INTO"
                        + " home_banner(item_key,title,asset_id,target_type,target_id,filters_json,sort_no,enabled,created_at,updated_at,version)"
                        + " VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    b.id(),
                    b.title(),
                    ContentSupport.id(b.assetId()),
                    b.target().type().name(),
                    b.target().id() == null ? null : ContentSupport.id(b.target().id()),
                    write(b.target().filters()),
                    b.sortNo(),
                    b.enabled());
        for (var s : r.shortcuts())
            db.update(
                    "INSERT INTO"
                        + " home_shortcut(item_key,label,asset_id,target_type,target_id,filters_json,sort_no,enabled,created_at,updated_at,version)"
                        + " VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    s.id(),
                    s.label(),
                    ContentSupport.id(s.assetId()),
                    s.target().type().name(),
                    s.target().id() == null ? null : ContentSupport.id(s.target().id()),
                    write(s.target().filters()),
                    s.sortNo(),
                    s.enabled());
        for (var s : r.sections()) {
            var key = new GeneratedKeyHolder();
            db.update(
                    connection -> {
                        var ps =
                                connection.prepareStatement(
                                        "INSERT INTO"
                                            + " home_section(item_key,type,title,more_target_json,article_id,sort_no,enabled,selection_mode,display_limit,pinned_ids,created_at,updated_at,version)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                                        Statement.RETURN_GENERATED_KEYS);
                        ps.setString(1, s.id());
                        ps.setString(2, s.type().name());
                        ps.setString(3, s.title());
                        ps.setString(4, s.moreTarget() == null ? null : write(s.moreTarget()));
                        ps.setObject(
                                5, s.articleId() == null ? null : ContentSupport.id(s.articleId()));
                        ps.setInt(6, s.sortNo());
                        ps.setBoolean(7, s.enabled());
                        ps.setString(8, s.selectionMode().name());
                        ps.setInt(9, s.displayLimit());
                        ps.setString(10, write(s.pinnedIds()));
                        return ps;
                    },
                    key);
            long sectionId = Objects.requireNonNull(key.getKey()).longValue();
            for (int i = 0; i < s.productIds().size(); i++)
                db.update(
                        "INSERT INTO"
                            + " home_section_item(section_id,product_id,sort_no,created_at,updated_at,version)"
                            + " VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        sectionId,
                        ContentSupport.id(s.productIds().get(i)),
                        i);
            var paperIds = s.paperIds() == null ? List.<String>of() : s.paperIds();
            for (int i = 0; i < paperIds.size(); i++)
                db.update(
                        "INSERT INTO"
                            + " home_section_paper(section_id,paper_id,sort_no,created_at,updated_at,version)"
                            + " VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        sectionId,
                        ContentSupport.id(paperIds.get(i)),
                        i);
        }
        support.audit(
                "HOME_DRAFT_EDIT", "HOME", 1, r.revision(), r.revision() + 1, "Edit home draft");
        return draft();
    }

    @Transactional
    public HomeDtos.View publish(HomeDtos.Publish r) {
        long revision =
                db.queryForObject(
                        "SELECT revision FROM home_draft WHERE id=1 FOR UPDATE", Long.class);
        if (revision != r.revision()) throw ContentSupport.stale();
        var current = draft();
        long enabledBanners = current.banners().stream().filter(HomeDtos.BannerInput::enabled).count();
        long enabledShortcuts =
                current.shortcuts().stream().filter(HomeDtos.ShortcutInput::enabled).count();
        if (enabledBanners != 3 || enabledShortcuts != 6 && enabledShortcuts != 8)
            throw ContentSupport.invalid(
                    "Published home requires exactly three banners and six shortcuts");
        for (var b : current.banners())
            if (b.enabled()) {
                assets.requirePurpose(ContentSupport.id(b.assetId()), "PUBLIC_IMAGE", false);
                targets.validate(b.target(), true);
            }
        for (var s : current.shortcuts())
            if (s.enabled()) {
                assets.requirePurpose(ContentSupport.id(s.assetId()), "PUBLIC_IMAGE", false);
                targets.validate(s.target(), true);
            }
        for (var s : current.sections())
            if (s.enabled()) {
                if (s.moreTarget() != null) targets.validate(s.moreTarget(), true);
                for (String product : s.selectionMode() == HomeDtos.SelectionMode.MANUAL ? s.productIds() : List.<String>of())
                    if (products.publishedCard(ContentSupport.id(product)) == null)
                        throw ContentSupport.invalid("Section product must be on sale");
                for (String paper : s.selectionMode() == HomeDtos.SelectionMode.MANUAL ? paperIds(s) : List.<String>of())
                    if (publishedPaper(ContentSupport.id(paper)) == null)
                        throw ContentSupport.invalid("Section paper must be published");
                if (s.type() == HomeDtos.SectionType.ARTICLE_CARD
                        && (s.articleId() == null
                                || articles.publishedSummary(ContentSupport.id(s.articleId()))
                                        == null))
                    throw ContentSupport.invalid("Section article must be published");
            }
        var view = render(current, revision + 1);
        db.update(
                "INSERT INTO"
                    + " home_layout(revision,modules_json,status,published_at,published_by,created_at,updated_at,version)"
                    + " VALUES(?,?,'PUBLISHED',UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                view.revision(),
                write(view),
                CurrentActor.require().id());
        db.update(
                "UPDATE home_draft SET"
                        + " revision=revision+1,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE"
                        + " id=1");
        db.update("INSERT INTO home_selection_snapshot(revision,rules_json) VALUES(?,?)",
                view.revision(), write(current));
        support.audit("HOME_PUBLISH", "HOME", 1, revision, revision + 1, r.reason());
        return view;
    }

    @Transactional(readOnly = true)
    public HomeDtos.View preview() {
        var d = draft();
        return render(d, d.revision());
    }

    @Transactional(readOnly = true)
    public HomeDtos.View published() {
        var rows =
                db.queryForList(
                        "SELECT modules_json FROM home_layout WHERE status='PUBLISHED' ORDER BY"
                                + " revision DESC LIMIT 1",
                        String.class);
        if (rows.isEmpty()) return new HomeDtos.View(0, List.of(), List.of(), List.of());
        var snapshot = read(rows.getFirst(), HomeDtos.View.class);
        snapshot = applyPublishedSelection(snapshot);
        return filterPublishedSnapshot(snapshot);
    }

    private HomeDtos.View applyPublishedSelection(HomeDtos.View snapshot) {
        var rules = db.queryForList("SELECT rules_json FROM home_selection_snapshot WHERE revision=?",
                String.class, snapshot.revision());
        if (!rules.isEmpty()) {
            var publishedRules = read(rules.getFirst(), HomeDtos.Draft.class);
            var automatic = new HashMap<String, HomeDtos.SectionInput>();
            publishedRules.sections().stream()
                    .filter(s -> s.selectionMode() == HomeDtos.SelectionMode.AUTO)
                    .forEach(s -> automatic.put(s.id(), s));
            return new HomeDtos.View(snapshot.revision(), snapshot.banners(), snapshot.shortcuts(),
                    snapshot.sections().stream().map(s -> automatic.containsKey(s.id())
                            ? renderSection(automatic.get(s.id())) : s).toList());
        }
        return snapshot;
    }

    private HomeDtos.View filterPublishedSnapshot(HomeDtos.View snapshot) {
        return new HomeDtos.View(
                snapshot.revision(),
                snapshot.banners(),
                snapshot.shortcuts(),
                snapshot.sections().stream()
                        .map(
                                s ->
                                        new HomeDtos.Section(
                                                s.id(),
                                                s.type(),
                                                s.title(),
                                                s.moreTarget(),
                                                s.products().stream()
                                                        .filter(
                                                                p ->
                                                                        products.publishedCard(
                                                                                        ContentSupport
                                                                                                .id(
                                                                                                        p
                                                                                                                .id()))
                                                                                != null)
                                                        .toList(),
                                                s.article() != null
                                                                && articles.publishedSummary(
                                                                                ContentSupport.id(
                                                                                        s.article()
                                                                                                .id()))
                                                                        != null
                                                        ? s.article()
                                                        : null,
                                                (s.papers() == null ? List.<PaperDtos.Summary>of() : s.papers())
                                                        .stream()
                                                        .filter(
                                                                p ->
                                                                        papers.publishedSummary(
                                                                                        ContentSupport.id(p.id()))
                                                                                != null)
                                                        .toList()))
                        .toList());
    }

    private HomeDtos.View render(HomeDtos.Draft d, long revision) {
        return new HomeDtos.View(
                revision,
                d.banners().stream()
                        .filter(HomeDtos.BannerInput::enabled)
                        .map(
                                b ->
                                        new HomeDtos.Banner(
                                                b.id(),
                                                assets.publicUrl(ContentSupport.id(b.assetId())),
                                                b.title(),
                                                b.target()))
                        .toList(),
                d.shortcuts().stream()
                        .filter(HomeDtos.ShortcutInput::enabled)
                        .map(
                                s ->
                                        new HomeDtos.Shortcut(
                                                s.id(),
                                                s.label(),
                                                assets.publicUrl(ContentSupport.id(s.assetId())),
                                                s.target()))
                        .toList(),
                d.sections().stream()
                        .filter(HomeDtos.SectionInput::enabled)
                        .map(this::renderSection)
                        .toList());
    }

    private PaperDtos.Summary publishedPaper(long id) {
        return papers.publishedSummary(id);
    }

    @Transactional
    public HomeDtos.Draft recommend(String sectionId, HomeDtos.Recommendation request) {
        long revision = db.queryForObject("SELECT revision FROM home_draft WHERE id=1 FOR UPDATE", Long.class);
        if (revision != request.revision()) throw ContentSupport.stale();
        var section = draft().sections().stream().filter(s -> s.id().equals(sectionId))
                .findFirst().orElseThrow(ContentSupport::missing);
        if (!automaticType(section)) throw ContentSupport.invalid("Section does not support recommendation");
        String contentId = Long.toString(ContentSupport.id(request.contentId()));
        boolean automatic = section.selectionMode() == HomeDtos.SelectionMode.AUTO;
        boolean paper = section.type() == HomeDtos.SectionType.PAPER_GRID;
        var ids = new ArrayList<>(automatic ? section.pinnedIds()
                : paper ? paperIds(section) : section.productIds());
        ids.removeIf(id -> ContentSupport.id(id) == ContentSupport.id(contentId));
        if (!request.remove()) ids.addFirst(contentId);
        uniqueKeys(ids);
        if (ids.size() > 50) throw ContentSupport.invalid("Too many recommendations");
        // Validate only the resulting target list; removal can repair an invalid selection.
        for (String id : ids) {
            long targetId = ContentSupport.id(id);
            if (paper) papers.adminGet(targetId);
            else {
                var product = products.getAdmin(targetId);
                Product.Type expected = section.type() == HomeDtos.SectionType.BOOK_GRID
                        ? Product.Type.BOOK : Product.Type.COURSE;
                if (product.type() != expected)
                    throw ContentSupport.invalid("Recommended content type mismatch");
            }
        }
        if (db.update("UPDATE home_draft SET revision=revision+1,version=version+1,"
                        + "updated_at=UTC_TIMESTAMP(6) WHERE id=1 AND revision=?", revision) != 1)
            throw ContentSupport.stale();
        long targetSectionId = db.queryForObject("SELECT id FROM home_section WHERE item_key=?",
                Long.class, sectionId);
        if (automatic) {
            db.update("UPDATE home_section SET pinned_ids=?,version=version+1,"
                    + "updated_at=UTC_TIMESTAMP(6) WHERE id=?", write(ids), targetSectionId);
        } else {
            String table = paper ? "home_section_paper" : "home_section_item";
            String column = paper ? "paper_id" : "product_id";
            db.update("DELETE FROM " + table + " WHERE section_id=?", targetSectionId);
            for (int i = 0; i < ids.size(); i++)
                db.update("INSERT INTO " + table + "(section_id," + column
                        + ",sort_no,created_at,updated_at,version)"
                        + " VALUES(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                        targetSectionId, ContentSupport.id(ids.get(i)), i);
            db.update("UPDATE home_section SET version=version+1,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE id=?", targetSectionId);
        }
        support.audit("HOME_DRAFT_EDIT", "HOME", 1, revision, revision + 1,
                "Update recommendation for section " + sectionId);
        return draft();
    }

    private boolean automaticType(HomeDtos.SectionInput s) {
        return s.type() == HomeDtos.SectionType.BOOK_GRID || s.type() == HomeDtos.SectionType.COURSE_GRID
                || s.type() == HomeDtos.SectionType.PAPER_GRID;
    }

    private void validateSelection(HomeDtos.SectionInput s) {
        uniqueKeys(s.pinnedIds());
        if (s.displayLimit() < 1 || s.displayLimit() > 50 || s.pinnedIds().size() > 50)
            throw ContentSupport.invalid("Invalid selection limit");
        if ((!automaticType(s) && s.selectionMode() == HomeDtos.SelectionMode.AUTO)
                || (s.selectionMode() == HomeDtos.SelectionMode.MANUAL && !s.pinnedIds().isEmpty()))
            throw ContentSupport.invalid("Invalid selection mode");
        for (String id : s.pinnedIds()) {
            if (s.type() == HomeDtos.SectionType.PAPER_GRID) papers.adminGet(ContentSupport.id(id));
            else {
                String expected = s.type() == HomeDtos.SectionType.BOOK_GRID ? "BOOK" : "COURSE";
                String type = db.queryForList("SELECT type FROM product WHERE id=?", String.class,
                        ContentSupport.id(id)).stream().findFirst().orElseThrow(ContentSupport::missing);
                if (!expected.equals(type)) throw ContentSupport.invalid("Pinned content type mismatch");
            }
        }
    }

    private List<String> paperIds(HomeDtos.SectionInput s) {
        return s.paperIds() == null ? List.of() : s.paperIds();
    }

    private List<String> readIds(String value) {
        if (value == null) return List.of();
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<String> selectedIds(HomeDtos.SectionInput s, boolean paper) {
        if (paper != (s.type() == HomeDtos.SectionType.PAPER_GRID)) return List.of();
        if (s.selectionMode() == HomeDtos.SelectionMode.MANUAL) return paper ? paperIds(s) : s.productIds();
        var result = new LinkedHashSet<String>();
        for (String id : s.pinnedIds()) {
            boolean available = paper ? publishedPaper(ContentSupport.id(id)) != null
                    : products.publishedCard(ContentSupport.id(id)) != null;
            if (available) result.add(id);
            if (result.size() == s.displayLimit()) return List.copyOf(result);
        }
        String sql = paper
                ? "SELECT CAST(id AS CHAR) FROM paper WHERE status='PUBLISHED' ORDER BY coalesce(first_published_at,created_at) DESC,id DESC LIMIT ?"
                : "SELECT CAST(id AS CHAR) FROM product WHERE status='ON_SALE' AND type='"
                        + (s.type() == HomeDtos.SectionType.BOOK_GRID ? "BOOK" : "COURSE")
                        + "' ORDER BY coalesce(first_published_at,created_at) DESC,id DESC LIMIT ?";
        result.addAll(db.queryForList(sql, String.class, s.displayLimit() + s.pinnedIds().size()));
        return result.stream().limit(s.displayLimit()).toList();
    }

    private HomeDtos.Section renderSection(HomeDtos.SectionInput s) {
        return new HomeDtos.Section(s.id(), s.type(), s.title(), s.moreTarget(),
                selectedIds(s, false).stream().map(id -> products.publishedCard(ContentSupport.id(id)))
                        .filter(Objects::nonNull).toList(),
                s.articleId() == null ? null : articles.publishedSummary(ContentSupport.id(s.articleId())),
                selectedIds(s, true).stream().map(id -> publishedPaper(ContentSupport.id(id)))
                        .filter(Objects::nonNull).toList());
    }

    private void uniqueKeys(List<String> ids) {
        Set<String> seen = new HashSet<>();
        for (String id : ids)
            if (id == null || id.isBlank() || !seen.add(id))
                throw ContentSupport.invalid("Duplicate content ID");
    }

    private HomeDtos.Target target(ResultSet r) throws SQLException {
        return new HomeDtos.Target(
                HomeDtos.TargetType.valueOf(r.getString("target_type")),
                r.getString("target_id"),
                readMap(r.getString("filters_json")));
    }

    private Map<String, String> readMap(String s) {
        try {
            return json.readValue(s, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String s, Class<T> t) {
        try {
            return json.readValue(s, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
