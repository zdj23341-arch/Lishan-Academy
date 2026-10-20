package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.PageQuery;
import com.wechat.jxsc.common.PageResult;
import com.wechat.jxsc.common.content.AdminListFilters;
import com.wechat.jxsc.common.content.ContentSupport;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class InventoryService {
    @PersistenceContext private EntityManager em;
    private final CommerceSupport support;

    public InventoryService(CommerceSupport support) {
        this.support = support;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "InventoryAdjustment")
    public record Adjustment(
            @NotNull @Min(-1000000000) @Max(1000000000) Integer delta,
            @NotBlank @Size(max = 500) String reason,
            @PositiveOrZero
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @io.swagger.v3.oas.annotations.media.Schema(description = "Expected book_inventory version from inventory read; optional for legacy atomic delta callers")
            Long version) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "InventoryView")
    public record View(String productId, int onHand, int reserved, int availableStock,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"integer", "null"}, description = "Inventory version at operation/read time; legacy stored operation results may have null")
            Long version) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "InventoryItem")
    public record Item(String productId, Product.Type type, String title, String status,
            long version, int onHand, int reserved, int availableStock) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Item> list(PageQuery page, String keyword) {
        keyword = AdminListFilters.keyword(keyword);
        Long searchId = AdminListFilters.searchId(keyword);
        String from = " from BookInventory b join Product p on p.id=b.productId where p.type=:type"
                + (keyword.isEmpty() ? "" : " and (locate(:keyword,p.title)>0" + (searchId == null ? "" : " or p.id=:searchId") + ")");
        var query = em.createQuery("select p,b" + from + " order by p.id desc", Object[].class);
        var count = em.createQuery("select count(b)" + from, Long.class);
        for (var q : List.of(query, count)) {
            q.setParameter("type", Product.Type.BOOK);
            if (!keyword.isEmpty()) q.setParameter("keyword", keyword);
            if (searchId != null) q.setParameter("searchId", searchId);
        }
        var items = query.setFirstResult(page.offset()).setMaxResults(page.pageSize()).getResultList()
                .stream().map(row -> {
                    var p = (Product) row[0];
                    var b = (BookInventory) row[1];
                    return new Item(p.getId().toString(), p.type, p.title, p.status,
                            b.getVersion(), b.onHand, b.reserved, b.onHand - b.reserved);
                }).toList();
        return new PageResult<>(items, page.page(), page.pageSize(), count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public View get(long productId) {
        var rows = em.createQuery("select b from BookInventory b join Product p on p.id=b.productId"
                        + " where p.id=:id and p.type=:type", BookInventory.class)
                .setParameter("id", productId).setParameter("type", Product.Type.BOOK).getResultList();
        if (rows.isEmpty()) throw CommerceSupport.missing();
        return view(rows.getFirst());
    }

    private View view(BookInventory b) {
        return new View(b.productId.toString(), b.onHand, b.reserved, b.onHand - b.reserved, b.getVersion());
    }

    private BookInventory lock(long productId) {
        // The ledger's product FK also locks its parent. Acquire that lock before inventory,
        // matching checkout and preventing a product/inventory lock-order inversion.
        var product = em.find(Product.class, productId, LockModeType.PESSIMISTIC_READ);
        if (product == null || product.type != Product.Type.BOOK)
            throw CommerceSupport.missing();
        var rows =
                em.createQuery(
                                "select b from BookInventory b where b.productId=:p",
                                BookInventory.class)
                        .setParameter("p", productId)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getResultList();
        if (rows.isEmpty()) throw CommerceSupport.missing();
        var b = rows.getFirst();
        em.refresh(b, LockModeType.PESSIMISTIC_WRITE);
        return b;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int available(long productId) {
        var b = lock(productId);
        return b.onHand - b.reserved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean change(
            long productId,
            Long itemId,
            String event,
            int onHandDelta,
            int reservedDelta,
            String key,
            String reason,
            boolean required) {
        var b = lock(productId);
        var existing =
                em.createQuery(
                                "select l from InventoryLedger l where l.businessKey=:k",
                                InventoryLedger.class)
                        .setParameter("k", key)
                        .setLockMode(LockModeType.PESSIMISTIC_READ)
                        .getResultList();
        if (!existing.isEmpty()) return true;
        long h = (long) b.onHand + onHandDelta, r = (long) b.reserved + reservedDelta;
        if (h < r || r < 0 || h > Integer.MAX_VALUE) {
            if (required)
                throw CommerceSupport.conflict(
                        "INSUFFICIENT_STOCK", "Insufficient available inventory");
            return false;
        }
        var l = new InventoryLedger();
        l.productId = productId;
        l.orderItemId = itemId;
        l.eventType = event;
        l.onHandDelta = onHandDelta;
        l.reservedDelta = reservedDelta;
        l.onHandBefore = b.onHand;
        l.reservedBefore = b.reserved;
        l.onHandAfter = (int) h;
        l.reservedAfter = (int) r;
        l.businessKey = key;
        l.reason = reason;
        b.onHand = (int) h;
        b.reserved = (int) r;
        em.persist(l);
        em.flush();
        return true;
    }

    @Transactional
    public View adjust(long id, Adjustment r, String key) {
        return support.mapper()
                .convertValue(
                        support.execute(
                                "INVENTORY_ADJUST",
                                key,
                                Map.of("productId", id, "request", r),
                                () -> {
                                     var before = lock(id);
                                     if (r.version() != null) ContentSupport.version(before.getVersion(), r.version());
                                    change(
                                            id,
                                            null,
                                            "ADJUST",
                                            r.delta(),
                                            0,
                                            "adjust:"
                                                    + com.wechat.jxsc.auth.CurrentActor.require()
                                                            .id()
                                                    + ":"
                                                    + key,
                                            r.reason(),
                                            true);
                                    var b = lock(id);
                                    support.audit(
                                            "INVENTORY_ADJUST",
                                            "PRODUCT",
                                            id,
                                            r.reason(),
                                            Map.of(
                                                    "delta",
                                                    r.delta(),
                                                    "onHand",
                                                    b.onHand,
                                                    "reserved",
                                                    b.reserved));
                                    return view(b);
                                }),
                        View.class);
    }
}
