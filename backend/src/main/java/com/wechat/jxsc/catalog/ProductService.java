package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.*;
import com.wechat.jxsc.common.storage.AssetService;
import com.wechat.jxsc.course.Course;

import jakarta.persistence.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ProductService {
    @PersistenceContext private EntityManager em;
    private final ContentSupport support;
    private final AssetService assets;
    private final BodyBlocks blocks;
    private final MembershipPlanService plans;
    private final CatalogConsistencyGuard guard;
    private final com.wechat.jxsc.membership.EntitlementAccess access;

    public ProductService(
            ContentSupport support,
            AssetService assets,
            BodyBlocks blocks,
            MembershipPlanService plans,
            CatalogConsistencyGuard guard,
            com.wechat.jxsc.membership.EntitlementAccess access) {
        this.support = support;
        this.assets = assets;
        this.blocks = blocks;
        this.plans = plans;
        this.guard = guard;
        this.access = access;
    }

    @Transactional
    public CatalogDtos.AdminProduct save(Long id, CatalogDtos.ProductWrite r) {
        guard.acquire();
        var p = id == null ? new Product() : guard.current(Product.class, id);
        long before = id == null ? -1 : p.getVersion();
        if (id != null) {
            ContentSupport.version(p.getVersion(), r.version());
            if (p.type != r.type()) throw ContentSupport.invalid("Product type is immutable");
        } else if (r.version() != 0) throw ContentSupport.stale();
        if (r.type() == Product.Type.BOOK) {
            if (r.book() == null || r.membership() != null)
                throw ContentSupport.invalid("BOOK requires book fields only");
        } else if (r.type() == Product.Type.MEMBERSHIP) {
            if (r.membership() == null || r.book() != null)
                throw ContentSupport.invalid("MEMBERSHIP requires plan fields only");
            MembershipPlanService.validateMonths(r.membership().durationMonths());
            if (id != null
                    && guard.currentPlanForProduct(id).durationMonths
                            != r.membership().durationMonths()) throw ContentSupport.stale();
        } else if (r.book() != null || r.membership() != null)
            throw ContentSupport.invalid("COURSE cannot include book or membership terms");
        Set<Long> categories = new LinkedHashSet<>();
        for (String value : r.categoryIds()) {
            long categoryId = ContentSupport.id(value);
            var c = guard.current(Category.class, categoryId);
            if (c.type != r.type() || !c.enabled)
                throw ContentSupport.invalid("Category must be enabled and match product type");
            if (!categories.add(categoryId)) throw ContentSupport.invalid("Duplicate category");
        }
        Long cover = r.coverAssetId() == null ? null : ContentSupport.id(r.coverAssetId());
        if (cover != null) assets.requirePurpose(cover, "PUBLIC_IMAGE", true);
        p.type = r.type();
        p.title = r.title();
        p.subtitle = r.subtitle();
        p.coverAssetId = cover;
        p.priceFen = r.priceFen();
        p.originalPriceFen = r.originalPriceFen();
        p.sortNo = r.sortNo();
        p.contentBlocks = blocks.validate(r.contentBlocks());
        p.categoryIds.clear();
        p.categoryIds.addAll(categories);
        p.quantityLimit = r.book() == null ? null : r.book().quantityLimit();
        p.shippingSummary = r.book() == null ? null : r.book().shippingSummary();
        if (id == null) {
            p.status = "DRAFT";
            em.persist(p);
            em.flush();
            if (p.type == Product.Type.BOOK) {
                var inv = new BookInventory();
                inv.productId = p.getId();
                em.persist(inv);
                var ledger = new InventoryLedger();
                ledger.productId = p.getId();
                ledger.eventType = "INITIAL";
                ledger.businessKey = "INITIAL:" + p.getId();
                ledger.reason = "Initialize book inventory";
                em.persist(ledger);
            }
            if (p.type == Product.Type.MEMBERSHIP) {
                var plan = new MembershipPlan();
                plan.productId = p.getId();
                plan.levelCode = "STANDARD";
                plan.durationMonths = r.membership().durationMonths();
                plan.status = "DISABLED";
                em.persist(plan);
            }
        }
        em.flush();
        support.audit(
                id == null ? "PRODUCT_CREATE" : "PRODUCT_EDIT",
                "PRODUCT",
                p.getId(),
                before,
                p.getVersion(),
                "Edit product");
        return admin(p);
    }

    @Transactional
    public CatalogDtos.AdminProduct lifecycle(
            long id, ContentSupport.Lifecycle r, boolean publish) {
        guard.acquire();
        var p = guard.current(Product.class, id);
        ContentSupport.version(p.getVersion(), r.version());
        if (!publish && p.status.equals("DRAFT"))
            throw new ApiException(
                    HttpStatus.CONFLICT, "INVALID_STATE", "A draft product cannot be unpublished");
        long before = p.getVersion();
        if (publish) {
            if (p.type == Product.Type.COURSE && course(id) == null)
                throw ContentSupport.invalid("Course terms are required");
            if (p.coverAssetId != null)
                assets.requirePurpose(p.coverAssetId, "PUBLIC_IMAGE", false);
            for (var block : p.contentBlocks)
                if (block.assetId() != null)
                    assets.requirePurpose(
                            ContentSupport.id(block.assetId()), "PUBLIC_IMAGE", false);
            if (p.type == Product.Type.MEMBERSHIP) {
                var m = guard.currentPlanForProduct(id);
                plans.ensureCycleFree(m.durationMonths, m.getId());
                m.status = "ENABLED";
            }
        } else if (p.type == Product.Type.MEMBERSHIP)
            guard.currentPlanForProduct(id).status = "DISABLED";
        p.status = publish ? "ON_SALE" : "OFF_SALE";
        em.flush();
        support.audit(
                publish ? "PRODUCT_PUBLISH" : "PRODUCT_UNPUBLISH",
                "PRODUCT",
                id,
                before,
                p.getVersion(),
                r.reason());
        return admin(p);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.AdminProduct getAdmin(long id) {
        return admin(ContentSupport.require(em, Product.class, id));
    }

    @Transactional(readOnly = true)
    public PageResult<CatalogDtos.AdminProduct> adminList(PageQuery page) {
        return adminList(page, null, null, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<CatalogDtos.AdminProduct> adminList(
            PageQuery page, String keyword, Product.Type type, CatalogDtos.ProductStatus status) {
        keyword = AdminListFilters.keyword(keyword);
        Long searchId = AdminListFilters.searchId(keyword);
        String where = " where 1=1"
                + (keyword.isEmpty() ? "" : " and (locate(:keyword,p.title)>0" + (searchId == null ? "" : " or p.id=:searchId") + ")")
                + (type == null ? "" : " and p.type=:type")
                + (status == null ? "" : " and p.status=:status");
        var q = em.createQuery("select p from Product p" + where + " order by p.id desc", Product.class);
        var count = em.createQuery("select count(p) from Product p" + where, Long.class);
        for (var query : List.of(q, count)) {
            if (!keyword.isEmpty()) query.setParameter("keyword", keyword);
            if (searchId != null) query.setParameter("searchId", searchId);
            if (type != null) query.setParameter("type", type);
            if (status != null) query.setParameter("status", status.name());
        }
        return new PageResult<>(
                q
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::admin)
                        .toList(),
                page.page(),
                page.pageSize(),
                count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public PageResult<CatalogDtos.Card> list(
            Product.Type type,
            String keyword,
            String category,
            CatalogDtos.Sort sort,
            PageQuery page) {
        if (keyword.length() > 200) throw ContentSupport.invalid("Keyword too long");
        String where =
                " where p.status='ON_SALE'"
                        + (type == null ? "" : " and p.type=:type")
                        + (keyword.isBlank() ? "" : " and locate(:keyword,p.title)>0")
                        + (category == null ? "" : " and :category member of p.categoryIds");
        String order =
                switch (sort) {
                    case DEFAULT -> "p.sortNo,p.id";
                    case PRICE_ASC -> "p.priceFen,p.id";
                    case PRICE_DESC -> "p.priceFen desc,p.id";
                    case NEWEST -> "p.createdAt desc,p.id desc";
                };
        var q =
                em.createQuery(
                        "select p from Product p" + where + " order by " + order, Product.class);
        var count = em.createQuery("select count(p) from Product p" + where, Long.class);
        for (var query : List.of(q, count)) {
            if (type != null) query.setParameter("type", type);
            if (!keyword.isBlank()) query.setParameter("keyword", keyword);
            if (category != null) query.setParameter("category", ContentSupport.id(category));
        }
        return new PageResult<>(
                q
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::card)
                        .toList(),
                page.page(),
                page.pageSize(),
                count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public CatalogDtos.Detail detail(long id) {
        var p = ContentSupport.require(em, Product.class, id);
        if (p.status.equals("DRAFT")) throw ContentSupport.missing();
        CatalogDtos.BookDetail book = null;
        CatalogDtos.CourseDetail course = null;
        CatalogDtos.MembershipDetail membership = null;
        if (p.type == Product.Type.BOOK) {
            var inv =
                    em.createQuery(
                                    "select i from BookInventory i where i.productId=:id",
                                    BookInventory.class)
                            .setParameter("id", id)
                            .getSingleResult();
            book =
                    new CatalogDtos.BookDetail(
                            inv.onHand - inv.reserved, p.quantityLimit, p.shippingSummary);
        }
        if (p.type == Product.Type.COURSE) {
            var c = course(id);
            course =
                    new CatalogDtos.CourseDetail(
                            c.getId().toString(),
                            c.validityType,
                            c.validityDays,
                            courseDurationMinutes(c),
                            access.course(
                                            com.wechat.jxsc.membership.EntitlementAccess.viewer(),
                                            c.getId())
                                    .status(),
                            em.createQuery(
                                            "select count(c) from CourseChapter c where"
                                                    + " c.courseId=:id and c.enabled=true",
                                            Long.class)
                                    .setParameter("id", c.getId())
                                    .getSingleResult()
                                    .intValue());
        }
        if (p.type == Product.Type.MEMBERSHIP)
            membership = new CatalogDtos.MembershipDetail(plan(id).durationMonths);
        return new CatalogDtos.Detail(
                p.getId().toString(),
                p.type,
                p.title,
                assets.publicUrl(p.coverAssetId),
                p.priceFen,
                p.originalPriceFen,
                p.status,
                p.subtitle,
                blocks.view(p.contentBlocks),
                p.getVersion(),
                course != null && course.entitlement().equals("ACTIVE")
                        ? List.of("LEARN")
                        : p.status.equals("ON_SALE") ? List.of("BUY") : List.of(),
                book,
                course,
                membership);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.Card publishedCard(long id) {
        var p = em.find(Product.class, id);
        return p != null && p.status.equals("ON_SALE") ? card(p) : null;
    }

    private CatalogDtos.Card card(Product p) {
        Integer duration = null;
        if (p.type == Product.Type.COURSE) {
            duration =
                    em.createQuery("select c from Course c where c.productId=:id", Course.class)
                            .setParameter("id", p.getId())
                            .getResultStream()
                            .findFirst()
                            .map(this::courseDurationMinutes)
                            .orElse(null);
        }
        return new CatalogDtos.Card(
                p.getId().toString(),
                p.type,
                p.title,
                assets.publicUrl(p.coverAssetId),
                p.priceFen,
                p.originalPriceFen,
                p.status,
                p.subtitle,
                duration);
    }

    private MembershipPlan plan(long id) {
        return em.createQuery(
                        "select m from MembershipPlan m where m.productId=:id",
                        MembershipPlan.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    private Course course(long id) {
        return em.createQuery("select c from Course c where c.productId=:id", Course.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private int courseDurationMinutes(Course c) {
        if (c.durationMinutes > 0) return c.durationMinutes;
        Long seconds =
                em.createQuery(
                                "select coalesce(sum(ch.durationSeconds), 0) from CourseChapter ch"
                                        + " where ch.courseId=:id and ch.enabled=true",
                                Long.class)
                        .setParameter("id", c.getId())
                        .getSingleResult();
        return Math.toIntExact((seconds + 59L) / 60L);
    }

    private CatalogDtos.AdminProduct admin(Product p) {
        // Standalone AdminProductController transaction boundary only. These consistent child
        // projections must not be composed into an outer transaction with an older RR snapshot.
        // Checkout uses its own current locking snapshot in CheckoutCatalog.
        var membership = p.type == Product.Type.MEMBERSHIP ? plan(p.getId()) : null;
        var inventory = p.type == Product.Type.BOOK
                ? em.createQuery("select b from BookInventory b where b.productId=:id", BookInventory.class)
                        .setParameter("id", p.getId()).getResultStream().findFirst().orElseThrow(ContentSupport::missing)
                : null;
        return new CatalogDtos.AdminProduct(
                p.getId().toString(),
                p.getVersion(),
                p.type,
                p.title,
                p.subtitle,
                p.coverAssetId == null ? null : p.coverAssetId.toString(),
                p.priceFen,
                p.originalPriceFen,
                p.status,
                p.sortNo,
                p.categoryIds.stream().map(Object::toString).toList(),
                p.contentBlocks,
                p.type == Product.Type.BOOK
                        ? new CatalogDtos.BookAdmin(p.quantityLimit, p.shippingSummary,
                                inventory.onHand - inventory.reserved, inventory.reserved,
                                inventory.onHand, inventory.getVersion())
                        : null,
                p.type == Product.Type.MEMBERSHIP
                        ? new CatalogDtos.MembershipAdmin(
                                membership.getId().toString(),
                                membership.getVersion(),
                                membership.durationMonths,
                                membership.status)
                        : null);
    }
}
