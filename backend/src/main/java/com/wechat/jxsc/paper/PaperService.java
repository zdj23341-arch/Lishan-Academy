package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.*;
import com.wechat.jxsc.common.storage.AssetService;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PaperService {
    @PersistenceContext private EntityManager em;
    private final ContentSupport support;
    private final AssetService assets;
    private final BodyBlocks blocks;
    private final com.wechat.jxsc.membership.EntitlementAccess access;

    public PaperService(
            ContentSupport support,
            AssetService assets,
            BodyBlocks blocks,
            com.wechat.jxsc.membership.EntitlementAccess access) {
        this.support = support;
        this.assets = assets;
        this.blocks = blocks;
        this.access = access;
    }

    @Transactional
    public PaperDtos.Admin save(Long id, PaperDtos.Write r) {
        var p = id == null ? new Paper() : ContentSupport.require(em, Paper.class, id);
        long before = id == null ? -1 : p.getVersion();
        if (id != null) ContentSupport.version(p.getVersion(), r.version());
        else if (r.version() != 0) throw ContentSupport.stale();
        Long cover = r.coverAssetId() == null ? null : ContentSupport.id(r.coverAssetId());
        if (cover != null) assets.requirePurpose(cover, "PUBLIC_IMAGE", true);
        String schoolStage = r.resolvedSchoolStage();
        if (schoolStage == null || schoolStage.isBlank())
            throw ContentSupport.invalid("School stage is required");
        String paperType =
                r.paperType() == null || r.paperType().isBlank() ? "PRACTICE" : r.paperType();
        Set<Long> attached = new HashSet<>();
        for (var a : r.attachments()) {
            long assetId = ContentSupport.id(a.assetId());
            if (!attached.add(assetId)) throw ContentSupport.invalid("Duplicate attachment");
            assets.requirePurpose(assetId, "PAPER_ATTACHMENT", true);
        }
        if (r.status() == PaperDtos.Status.PUBLISHED && attached.isEmpty())
            throw ContentSupport.invalid("Published paper requires ready attachments");
        p.title = r.title();
        p.stage = schoolStage;
        p.schoolStage = schoolStage;
        p.grade = r.grade();
        p.subject = r.subject();
        p.paperType = paperType;
        p.year = r.year();
        p.summary = r.summary() == null ? "" : r.summary();
        p.status = r.status().name();
        p.visibility = "MEMBER";
        p.coverAssetId = cover;
        p.contentBlocks = blocks.validate(r.contentBlocks());
        if (id == null) em.persist(p);
        em.flush();
        if (id != null) p.attachmentRevision++;
        // Relationship changes keep asset records; future download audits reference immutable asset
        // IDs.
        for (var old : attachments(p.getId())) em.remove(old);
        em.flush();
        for (var a : r.attachments()) {
            var rel = new PaperAttachment();
            rel.paperId = p.getId();
            rel.assetId = ContentSupport.id(a.assetId());
            rel.title = a.title();
            rel.sortNo = a.sortNo();
            em.persist(rel);
        }
        em.flush();
        support.audit(
                id == null ? "PAPER_CREATE" : "PAPER_EDIT",
                "PAPER",
                p.getId(),
                before,
                p.getVersion(),
                "Edit paper");
        return admin(p);
    }

    @Transactional(readOnly = true)
    public PaperDtos.Admin adminGet(long id) {
        return admin(ContentSupport.require(em, Paper.class, id));
    }

    @Transactional(readOnly = true)
    public PageResult<PaperDtos.Admin> adminList(PageQuery p) {
        return adminList(p, "", null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public PageResult<PaperDtos.Admin> adminList(
            PageQuery p,
            String keyword,
            String schoolStage,
            String grade,
            String subject,
            Integer year,
            String paperType,
            PaperDtos.Status status) {
        keyword = keyword == null ? "" : keyword;
        if (keyword.length() > 200
                || (schoolStage != null && schoolStage.length() > 40)
                || (grade != null && grade.length() > 20)
                || (subject != null && subject.length() > 40)
                || (paperType != null && paperType.length() > 40)
                || (year != null && (year < 1900 || year > 2200)))
            throw ContentSupport.invalid("Invalid paper filters");
        String where =
                " where 1=1"
                        + (keyword.isBlank() ? "" : " and locate(:keyword,p.title)>0")
                        + (schoolStage == null ? "" : " and p.schoolStage=:schoolStage")
                        + (grade == null ? "" : " and p.grade=:grade")
                        + (subject == null ? "" : " and p.subject=:subject")
                        + (year == null ? "" : " and p.year=:year")
                        + (paperType == null ? "" : " and p.paperType=:paperType")
                        + (status == null ? "" : " and p.status=:status");
        var query =
                em.createQuery(
                        "select p from Paper p" + where + " order by p.id desc", Paper.class);
        var count = em.createQuery("select count(p) from Paper p" + where, Long.class);
        for (var candidate : List.of(query, count)) {
            if (!keyword.isBlank()) candidate.setParameter("keyword", keyword);
            if (schoolStage != null) candidate.setParameter("schoolStage", schoolStage);
            if (grade != null) candidate.setParameter("grade", grade);
            if (subject != null) candidate.setParameter("subject", subject);
            if (year != null) candidate.setParameter("year", year);
            if (paperType != null) candidate.setParameter("paperType", paperType);
            if (status != null) candidate.setParameter("status", status.name());
        }
        return new PageResult<>(
                query.setFirstResult(p.offset()).setMaxResults(p.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::admin)
                        .toList(),
                p.page(),
                p.pageSize(),
                count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public PaperDtos.View detail(long id) {
        var p = ContentSupport.require(em, Paper.class, id);
        if (!p.status.equals("PUBLISHED")) throw ContentSupport.missing();
        var files =
                attachments(id).stream()
                        .map(
                                a -> {
                                    var f = assets.readySummary(a.assetId, "PAPER_ATTACHMENT");
                                    if (f == null) return null;
                                    return new PaperDtos.Attachment(
                                            f.id(),
                                            f.name(),
                                            f.extension(),
                                            f.mime(),
                                            f.bytes(),
                                            a.title,
                                            access.canAccessPaper(
                                                    com.wechat.jxsc.membership.EntitlementAccess
                                                            .viewer()),
                                            access.canAccessPaper(
                                                    com.wechat.jxsc.membership.EntitlementAccess
                                                            .viewer()));
                                })
                        .filter(java.util.Objects::nonNull)
                        .toList();
        return new PaperDtos.View(
                p.getId().toString(),
                p.title,
                p.stage,
                p.schoolStage,
                p.grade,
                p.subject,
                p.year,
                p.paperType,
                p.summary,
                p.visibility,
                assets.publicUrl(p.coverAssetId),
                blocks.view(p.contentBlocks),
                files,
                "ACTIVE_MEMBER",
                resourceStatus(id));
    }

    public PageResult<PaperDtos.Summary> list(
            String keyword, String grade, Integer year, String subject, PageQuery p) {
        return list(keyword, null, grade, year, subject, null, p);
    }

    @Transactional(readOnly = true)
    public PageResult<PaperDtos.Summary> list(
            String keyword,
            String schoolStage,
            String grade,
            Integer year,
            String subject,
            String paperType,
            PageQuery p) {
        if (keyword.length() > 200
                || (schoolStage != null && schoolStage.length() > 40)
                || (grade != null && grade.length() > 20)
                || (subject != null && subject.length() > 40)
                || (paperType != null && paperType.length() > 40)
                || (year != null && (year < 1900 || year > 2200)))
            throw ContentSupport.invalid("Invalid paper filters");
        String where =
                " where p.status='PUBLISHED'"
                        + (keyword.isBlank() ? "" : " and locate(:keyword,p.title)>0")
                        + (schoolStage == null ? "" : " and p.schoolStage=:schoolStage")
                        + (grade == null ? "" : " and p.grade=:grade")
                        + (year == null ? "" : " and p.year=:year")
                        + (subject == null ? "" : " and p.subject=:subject")
                        + (paperType == null ? "" : " and p.paperType=:paperType");
        var q =
                em.createQuery(
                        "select p from Paper p" + where + " order by p.year desc,p.id desc",
                        Paper.class);
        var count = em.createQuery("select count(p) from Paper p" + where, Long.class);
        for (var query : List.of(q, count)) {
            if (!keyword.isBlank()) query.setParameter("keyword", keyword);
            if (schoolStage != null) query.setParameter("schoolStage", schoolStage);
            if (grade != null) query.setParameter("grade", grade);
            if (year != null) query.setParameter("year", year);
            if (subject != null) query.setParameter("subject", subject);
            if (paperType != null) query.setParameter("paperType", paperType);
        }
        return new PageResult<>(
                q.setFirstResult(p.offset()).setMaxResults(p.pageSize()).getResultList().stream()
                        .map(
                                a ->
                                        new PaperDtos.Summary(
                                                a.getId().toString(),
                                                a.title,
                                                a.stage,
                                                a.schoolStage,
                                                a.grade,
                                                a.subject,
                                                a.year,
                                                a.paperType,
                                                a.summary,
                                                assets.publicUrl(a.coverAssetId),
                                                attachmentFormats(a.getId()),
                                                "ACTIVE_MEMBER",
                                                resourceStatus(a.getId())))
                        .toList(),
                p.page(),
                p.pageSize(),
                count.getSingleResult());
    }

    @Transactional(readOnly = true)
    public PaperDtos.Summary publishedSummary(long id) {
        var paper = em.find(Paper.class, id);
        return paper == null || !"PUBLISHED".equals(paper.status) ? null : summary(paper);
    }

    private List<PaperAttachment> attachments(long id) {
        return em.createQuery(
                        "select a from PaperAttachment a where a.paperId=:id order by"
                                + " a.sortNo,a.id",
                        PaperAttachment.class)
                .setParameter("id", id)
                .getResultList();
    }

    private List<String> attachmentFormats(long id) {
        return attachments(id).stream()
                .map(a -> assets.readySummary(a.assetId, "PAPER_ATTACHMENT"))
                .filter(Objects::nonNull)
                .map(value -> value.extension())
                .distinct()
                .toList();
    }

    private String resourceStatus(long id) {
        var all = attachments(id);
        if (all.isEmpty()) return "MISSING";
        long ready =
                all.stream()
                        .filter(a -> assets.readySummary(a.assetId, "PAPER_ATTACHMENT") != null)
                        .count();
        return ready == all.size() ? "READY" : ready == 0 ? "NOT_READY" : "PARTIAL";
    }

    private PaperDtos.Summary summary(Paper paper) {
        return new PaperDtos.Summary(
                paper.getId().toString(),
                paper.title,
                paper.stage,
                paper.schoolStage,
                paper.grade,
                paper.subject,
                paper.year,
                paper.paperType,
                paper.summary,
                assets.publicUrl(paper.coverAssetId),
                attachmentFormats(paper.getId()),
                "ACTIVE_MEMBER",
                resourceStatus(paper.getId()));
    }

    private PaperDtos.Admin admin(Paper p) {
        return new PaperDtos.Admin(
                p.getId().toString(),
                p.getVersion(),
                p.title,
                p.stage,
                p.schoolStage,
                p.grade,
                p.subject,
                p.year,
                p.paperType,
                p.summary,
                PaperDtos.Status.valueOf(p.status),
                p.coverAssetId == null ? null : p.coverAssetId.toString(),
                p.contentBlocks,
                attachments(p.getId()).stream()
                        .map(
                                a ->
                                        new PaperDtos.AttachmentInput(
                                                a.assetId.toString(), a.title, a.sortNo))
                        .toList());
    }
}
