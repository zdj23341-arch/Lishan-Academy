package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;
import com.wechat.jxsc.common.content.AdminListFilters;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CategoryService {
    @PersistenceContext private EntityManager em;
    private final ContentSupport support;
    private final CatalogConsistencyGuard guard;

    public CategoryService(ContentSupport support, CatalogConsistencyGuard guard) {
        this.support = support;
        this.guard = guard;
    }

    @Transactional
    public CatalogDtos.CategoryView save(Long id, CatalogDtos.CategoryWrite r) {
        guard.acquire();
        var c = id == null ? new Category() : guard.current(Category.class, id);
        if (id != null) ContentSupport.version(c.getVersion(), r.version());
        else if (r.version() != 0) throw ContentSupport.stale();
        long before = id == null ? -1 : c.getVersion();
        Long parent = r.parentId() == null ? null : ContentSupport.id(r.parentId());
        if (parent != null) {
            var p = guard.current(Category.class, parent);
            if (p.type != r.type()) throw ContentSupport.invalid("Parent category type differs");
            Set<Long> seen = new HashSet<>();
            while (p != null) {
                if (Objects.equals(p.getId(), id) || !seen.add(p.getId()))
                    throw ContentSupport.invalid("Category cycle");
                p = p.parentId == null ? null : guard.current(Category.class, p.parentId);
            }
        }
        if (id != null && c.type != r.type()) {
            if (guard.categoryIsReferenced(id))
                throw ContentSupport.invalid("Referenced category type cannot change");
        }
        c.name = r.name();
        c.type = r.type();
        c.parentId = parent;
        c.sortNo = r.sortNo();
        c.enabled = r.enabled();
        if (id == null) em.persist(c);
        em.flush();
        support.audit(
                id == null ? "CATEGORY_CREATE" : "CATEGORY_EDIT",
                "CATEGORY",
                c.getId(),
                before,
                c.getVersion(),
                "Edit category");
        return view(c);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.CategoryView get(long id) {
        return view(ContentSupport.require(em, Category.class, id));
    }

    @Transactional(readOnly = true)
    public PageResult<CatalogDtos.CategoryView> list(
            boolean admin, Product.Type type, PageQuery page) {
        return list(admin, type, page, null, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<CatalogDtos.CategoryView> list(
            boolean admin, Product.Type type, PageQuery page, String keyword, Boolean enabled) {
        keyword = AdminListFilters.keyword(keyword);
        String where =
                (admin ? "" : " and c.enabled=true") + (type == null ? "" : " and c.type=:type")
                        + (keyword.isEmpty() ? "" : " and locate(:keyword,c.name)>0")
                        + (admin && enabled != null ? " and c.enabled=:enabled" : "");
        var q =
                em.createQuery(
                        "select c from Category c where 1=1" + where + " order by c.sortNo,c.id",
                        Category.class);
        var count = em.createQuery("select count(c) from Category c where 1=1" + where, Long.class);
        if (type != null) {
            q.setParameter("type", type);
            count.setParameter("type", type);
        }
        for (var query : List.of(q, count)) {
            if (!keyword.isEmpty()) query.setParameter("keyword", keyword);
            if (admin && enabled != null) query.setParameter("enabled", enabled);
        }
        return new PageResult<>(
                q
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::view)
                        .toList(),
                page.page(),
                page.pageSize(),
                count.getSingleResult());
    }

    private CatalogDtos.CategoryView view(Category c) {
        return new CatalogDtos.CategoryView(
                c.getId().toString(),
                c.getVersion(),
                c.name,
                c.type,
                c.parentId == null ? null : c.parentId.toString(),
                c.sortNo,
                c.enabled);
    }
}
