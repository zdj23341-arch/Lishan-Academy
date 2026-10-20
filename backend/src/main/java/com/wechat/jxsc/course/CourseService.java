package com.wechat.jxsc.course;

import com.wechat.jxsc.catalog.Product;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;
import com.wechat.jxsc.common.storage.AssetService;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CourseService {
    @PersistenceContext private EntityManager em;
    private final ContentSupport support;
    private final AssetService assets;
    private final com.wechat.jxsc.membership.EntitlementAccess access;

    public CourseService(
            ContentSupport support,
            AssetService assets,
            com.wechat.jxsc.membership.EntitlementAccess access) {
        this.support = support;
        this.assets = assets;
        this.access = access;
    }

    @Transactional
    public CourseDtos.View save(Long id, CourseDtos.Write r) {
        var c = id == null ? new Course() : ContentSupport.require(em, Course.class, id);
        long before = id == null ? -1 : c.getVersion();
        if (id != null) ContentSupport.version(c.getVersion(), r.version());
        else if (r.version() != 0) throw ContentSupport.stale();
        long productId = ContentSupport.id(r.productId());
        var product = ContentSupport.require(em, Product.class, productId);
        if (product.type != Product.Type.COURSE)
            throw ContentSupport.invalid("Product must have COURSE type");
        if (id != null && !c.productId.equals(productId))
            throw ContentSupport.invalid("Course product is immutable");
        if ((r.validityType() == CourseDtos.Validity.DAYS && r.validityDays() == null)
                || (r.validityType() == CourseDtos.Validity.PERMANENT && r.validityDays() != null))
            throw ContentSupport.invalid("DAYS needs positive days; PERMANENT needs null days");
        c.productId = productId;
        c.validityType = r.validityType().name();
        c.validityDays = r.validityDays();
        if (r.durationMinutes() != null) c.durationMinutes = r.durationMinutes();
        c.teacherName = r.teacherName();
        if (id == null) em.persist(c);
        em.flush();
        if (id == null)
            em.createNativeQuery("insert into entitlement_course_target(course_id) values (:id)")
                    .setParameter("id", c.getId())
                    .executeUpdate();
        support.audit(
                id == null ? "COURSE_CREATE" : "COURSE_EDIT",
                "COURSE",
                c.getId(),
                before,
                c.getVersion(),
                "Edit course terms");
        return view(c);
    }

    @Transactional(readOnly = true)
    public CourseDtos.View get(long id) {
        return view(ContentSupport.require(em, Course.class, id));
    }

    @Transactional(readOnly = true)
    public PageResult<CourseDtos.View> list(PageQuery p) {
        return new PageResult<>(
                em
                        .createQuery("select c from Course c order by c.id desc", Course.class)
                        .setFirstResult(p.offset())
                        .setMaxResults(p.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::view)
                        .toList(),
                p.page(),
                p.pageSize(),
                em.createQuery("select count(c) from Course c", Long.class).getSingleResult());
    }

    @Transactional
    public CourseDtos.ChapterAdmin chapter(Long id, long courseId, CourseDtos.ChapterWrite r) {
        var c = em.find(Course.class, courseId, LockModeType.PESSIMISTIC_WRITE);
        if (c == null) throw ContentSupport.missing();
        var ch =
                id == null
                        ? new CourseChapter()
                        : ContentSupport.require(em, CourseChapter.class, id);
        long before = id == null ? -1 : ch.getVersion();
        if (id != null) {
            ContentSupport.version(ch.getVersion(), r.version());
            if (!ch.courseId.equals(courseId)) throw ContentSupport.missing();
        } else if (r.version() != 0) throw ContentSupport.stale();
        Long video = r.videoAssetId() == null ? null : ContentSupport.id(r.videoAssetId());
        if (video != null) assets.requirePurpose(video, "COURSE_VIDEO", true);
        ch.courseId = courseId;
        ch.title = r.title();
        ch.sortNo = r.sortNo();
        ch.videoAssetId = video;
        ch.durationSeconds = r.durationSeconds();
        ch.isTrial = r.isTrial();
        ch.enabled = r.enabled();
        if (id == null) em.persist(ch);
        c.chapterRevision++;
        em.flush();
        support.audit(
                id == null ? "CHAPTER_CREATE" : "CHAPTER_EDIT",
                "COURSE_CHAPTER",
                ch.getId(),
                before,
                ch.getVersion(),
                "Edit chapter metadata");
        return admin(ch);
    }

    @Transactional
    public CourseDtos.ChapterAdmin editChapter(long id, CourseDtos.ChapterWrite r) {
        var ch = ContentSupport.require(em, CourseChapter.class, id);
        return chapter(id, ch.courseId, r);
    }

    @Transactional
    public CourseDtos.View order(long id, CourseDtos.ChapterOrder r) {
        var c = em.find(Course.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (c == null) throw ContentSupport.missing();
        ContentSupport.version(c.getVersion(), r.version());
        var chapters = chapters(id);
        var ids = r.chapters().stream().map(ContentSupport::id).toList();
        if (new HashSet<>(ids).size() != ids.size()
                || !new HashSet<>(ids)
                        .equals(
                                new HashSet<>(
                                        chapters.stream().map(CourseChapter::getId).toList())))
            throw ContentSupport.invalid("Order must contain every chapter exactly once");
        long before = c.getVersion();
        Map<Long, CourseChapter> byId = new HashMap<>();
        chapters.forEach(ch -> byId.put(ch.getId(), ch));
        for (int i = 0; i < ids.size(); i++) byId.get(ids.get(i)).sortNo = i;
        c.chapterRevision++;
        em.flush();
        support.audit("CHAPTER_REORDER", "COURSE", id, before, c.getVersion(), "Reorder chapters");
        return view(c);
    }

    @Transactional(readOnly = true)
    public PageResult<CourseDtos.ChapterAdmin> adminChapters(long id, PageQuery p) {
        ContentSupport.require(em, Course.class, id);
        var all = chapters(id);
        return new PageResult<>(
                all.stream().skip(p.offset()).limit(p.pageSize()).map(this::admin).toList(),
                p.page(),
                p.pageSize(),
                all.size());
    }

    @Transactional(readOnly = true)
    public PageResult<CourseDtos.ChapterView> publicChapters(long id, PageQuery p) {
        var course = ContentSupport.require(em, Course.class, id);
        if (ContentSupport.require(em, Product.class, course.productId).status.equals("DRAFT"))
            throw ContentSupport.missing();
        var all = chapters(id).stream().filter(c -> c.enabled).toList();
        return new PageResult<>(
                all.stream()
                        .skip(p.offset())
                        .limit(p.pageSize())
                        .map(
                                c ->
                                        new CourseDtos.ChapterView(
                                                c.getId().toString(),
                                                c.title,
                                                c.durationSeconds,
                                                c.isTrial,
                                                access.canPlay(
                                                        com.wechat.jxsc.membership.EntitlementAccess
                                                                .viewer(),
                                                        id,
                                                        c.getId()),
                                                access.position(
                                                        com.wechat.jxsc.membership.EntitlementAccess
                                                                .viewer(),
                                                        c.getId())))
                        .toList(),
                p.page(),
                p.pageSize(),
                all.size());
    }

    private List<CourseChapter> chapters(long id) {
        return em.createQuery(
                        "select c from CourseChapter c where c.courseId=:id order by c.sortNo,c.id",
                        CourseChapter.class)
                .setParameter("id", id)
                .getResultList();
    }

    private CourseDtos.View view(Course c) {
        return new CourseDtos.View(
                c.getId().toString(),
                c.getVersion(),
                c.productId.toString(),
                CourseDtos.Validity.valueOf(c.validityType),
                c.validityDays,
                c.teacherName,
                durationMinutes(c));
    }

    private int durationMinutes(Course c) {
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

    private CourseDtos.ChapterAdmin admin(CourseChapter c) {
        return new CourseDtos.ChapterAdmin(
                c.getId().toString(),
                c.getVersion(),
                c.courseId.toString(),
                c.title,
                c.sortNo,
                c.videoAssetId == null ? null : c.videoAssetId.toString(),
                c.durationSeconds,
                c.isTrial,
                c.enabled);
    }
}
