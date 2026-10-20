package com.wechat.jxsc.article;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.*;
import com.wechat.jxsc.common.storage.AssetService;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class ArticleService {
    @PersistenceContext private EntityManager em;
    private final ContentSupport support;
    private final AssetService assets;
    private final BodyBlocks blocks;
    private final Clock clock;

    public ArticleService(
            ContentSupport support, AssetService assets, BodyBlocks blocks, Clock clock) {
        this.support = support;
        this.assets = assets;
        this.blocks = blocks;
        this.clock = clock;
    }

    @Transactional
    public ArticleDtos.Admin save(Long id, ArticleDtos.Write r) {
        var a = id == null ? new Article() : ContentSupport.require(em, Article.class, id);
        long before = id == null ? -1 : a.getVersion();
        if (id != null) ContentSupport.version(a.getVersion(), r.version());
        else if (r.version() != 0) throw ContentSupport.stale();
        Long cover = r.coverAssetId() == null ? null : ContentSupport.id(r.coverAssetId());
        if (cover != null) assets.requirePurpose(cover, "PUBLIC_IMAGE", true);
        a.title = r.title();
        if (r.authorName() != null) a.authorName = nullableText(r.authorName());
        if (r.categoryName() != null) a.categoryName = nullableText(r.categoryName());
        a.coverAssetId = cover;
        a.contentBlocks = blocks.validate(r.contentBlocks());
        a.status = r.status().name();
        if (a.status.equals("PUBLISHED") && a.publishedAt == null) a.publishedAt = clock.instant();
        if (id == null) em.persist(a);
        em.flush();
        // MySQL stores microseconds; return the persisted timestamps consistently on writes/reads.
        em.refresh(a);
        support.audit(
                id == null ? "ARTICLE_CREATE" : "ARTICLE_EDIT",
                "ARTICLE",
                a.getId(),
                before,
                a.getVersion(),
                "Edit article");
        return admin(a);
    }

    @Transactional(readOnly = true)
    public ArticleDtos.Admin adminGet(long id) {
        return admin(ContentSupport.require(em, Article.class, id));
    }

    @Transactional(readOnly = true)
    public PageResult<ArticleDtos.Admin> list(PageQuery p) {
        return list(p, null, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<ArticleDtos.Admin> list(PageQuery p, String keyword, ArticleDtos.Status status) {
        keyword = AdminListFilters.keyword(keyword);
        String where = " where 1=1"
                + (keyword.isEmpty() ? "" : " and locate(:keyword,a.title)>0")
                + (status == null ? "" : " and a.status=:status");
        var query = em.createQuery("select a from Article a" + where + " order by a.id desc", Article.class);
        var count = em.createQuery("select count(a) from Article a" + where, Long.class);
        for (var q : java.util.List.of(query, count)) {
            if (!keyword.isEmpty()) q.setParameter("keyword", keyword);
            if (status != null) q.setParameter("status", status.name());
        }
        return new PageResult<>(
                query
                        .setFirstResult(p.offset())
                        .setMaxResults(p.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::admin)
                        .toList(),
                p.page(),
                p.pageSize(),
                count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public ArticleDtos.View detail(long id) {
        var a = ContentSupport.require(em, Article.class, id);
        if (!a.status.equals("PUBLISHED")) throw ContentSupport.missing();
        return new ArticleDtos.View(
                a.getId().toString(),
                a.title,
                assets.publicUrl(a.coverAssetId),
                blocks.view(a.contentBlocks),
                a.publishedAt,
                a.authorName,
                a.categoryName,
                a.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public ArticleDtos.Summary publishedSummary(long id) {
        var a = em.find(Article.class, id);
        return a != null && a.status.equals("PUBLISHED")
                ? new ArticleDtos.Summary(
                        a.getId().toString(), a.title, assets.publicUrl(a.coverAssetId))
                : null;
    }

    private ArticleDtos.Admin admin(Article a) {
        return new ArticleDtos.Admin(
                a.getId().toString(),
                a.getVersion(),
                a.title,
                a.coverAssetId == null ? null : a.coverAssetId.toString(),
                ArticleDtos.Status.valueOf(a.status),
                a.contentBlocks,
                a.publishedAt,
                a.authorName,
                a.categoryName,
                a.getUpdatedAt());
    }

    private static String nullableText(String value) {
        return value.isBlank() ? null : value.strip();
    }
}
