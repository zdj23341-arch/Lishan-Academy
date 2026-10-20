package com.wechat.jxsc.course;

import static com.wechat.jxsc.membership.EntitlementLedger.time;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.membership.*;

import jakarta.validation.constraints.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.*;
import java.util.*;

@Service
public class LearningService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    private final JdbcTemplate db;
    private final EntitlementLedger ledger;
    private final EntitlementAccess access;
    private final Clock clock;

    public LearningService(
            JdbcTemplate db, EntitlementLedger ledger, EntitlementAccess access, Clock clock) {
        this.db = db;
        this.ledger = ledger;
        this.access = access;
        this.clock = clock;
    }

    public record ProgressWrite(
            @NotNull @Min(0) @Max(86400) Integer positionSeconds, Boolean completed) {}

    public record Progress(
            String chapterId, int positionSeconds, boolean completed, Instant lastLearnedAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "LearningResume")
    public record Resume(String chapterId, String chapterTitle, int positionSeconds, Instant lastLearnedAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "LearningCourseView")
    public record CourseView(
            String courseId,
            String productId,
            String title,
            String status,
            @io.swagger.v3.oas.annotations.media.Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant expiresAt,
            int completedChapters,
            int totalChapters,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Latest enabled chapter progress for the authenticated user; null when no saved record")
            Resume resume) {}

    @Transactional
    public Progress save(long chapterId, ProgressWrite body) {
        lifecycle.customer();
        long user = EntitlementAccess.customer();
        var chapter = access.chapter(chapterId);
        // Trial playback never grants progress-write rights. No subject creation is needed here.
        EntitlementAccess.require(
                access.course(user, chapter.courseId()), "Purchase this course to save progress");
        ledger.lock(user);
        chapter = access.chapter(chapterId, true);
        EntitlementAccess.require(
                access.course(user, chapter.courseId(), true),
                "Purchase this course to save progress");
        if (!chapter.enabled()) throw EntitlementAccess.contentUnavailable();
        // Clamp instead of rejecting. Players report whole seconds and routinely stop one
        // second short of the end, and an admin can shorten a chapter after customers have
        // already reached the old end. Rejecting those made a finished chapter unsavable and
        // left "My learning" stuck on in-progress forever.
        int duration = chapter.durationSeconds();
        int position = Math.max(0, Math.min(body.positionSeconds(), duration));
        // A chapter with no declared duration cannot be completed: 0 >= 0 would otherwise
        // mark every not-yet-configured chapter as watched the moment it is opened.
        boolean completed = duration > 0 && position >= duration;
        var existing =
                db.query(
                        "select position_seconds,completed,last_learned_at from learning_progress"
                                + " where user_id=? and chapter_id=? for update",
                        (r, n) ->
                                new Progress(
                                        Long.toString(chapterId),
                                        r.getInt(1),
                                        r.getBoolean(2),
                                        r.getTimestamp(3).toInstant()),
                        user,
                        chapterId);
        // A repeated position is a no-op only while completion still matches current duration.
        if (!existing.isEmpty()
                && existing.getFirst().positionSeconds() == position
                && existing.getFirst().completed() == completed) return existing.getFirst();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update(
                "insert into"
                    + " learning_progress(user_id,chapter_id,position_seconds,completed,last_learned_at)"
                    + " values (?,?,?,?,?) on duplicate key update"
                    + " position_seconds=values(position_seconds),completed=values(completed),last_learned_at=values(last_learned_at),version=version+1,updated_at=UTC_TIMESTAMP(6)",
                user,
                chapterId,
                position,
                completed,
                time(now));
        return new Progress(Long.toString(chapterId), position, completed, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<CourseView> courses(PageQuery p) {
        return courses(p, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<CourseView> courses(PageQuery p, LearningFilter filter) {
        long user = EntitlementAccess.customer();
        if (filter != null) return filteredCourses(user, p, filter);
        var ids =
                db.queryForList(
                        "select distinct target_id from entitlement where user_id=? and"
                                + " entitlement_type='COURSE_VIEW' order by target_id desc limit ?"
                                + " offset ?",
                        Long.class,
                        user,
                        p.pageSize(),
                        p.offset());
        var items =
                ids.stream()
                        .map(
                                id -> {
                                    var c =
                                            db.queryForMap(
                                                    "select c.product_id,p.title from course c join"
                                                        + " product p on p.id=c.product_id where"
                                                        + " c.id=?",
                                                    id);
                                    var right = access.course(user, id);
                                    int total =
                                            db.queryForObject(
                                                    "select count(*) from course_chapter where"
                                                            + " course_id=? and enabled=true",
                                                    Integer.class,
                                                    id);
                                    int done =
                                            db.queryForObject(
                                                    "select count(*) from learning_progress l join"
                                                        + " course_chapter c on c.id=l.chapter_id"
                                                        + " where l.user_id=? and c.course_id=? and"
                                                        + " c.enabled=true and c.duration_seconds>0 and"
                                                        + " l.position_seconds>=c.duration_seconds",
                                                    Integer.class,
                                                    user,
                                                    id);
                                    return new CourseView(
                                            id.toString(),
                                            c.get("product_id").toString(),
                                            c.get("title").toString(),
                                            right.status(),
                                            right.expiresAt(),
                                            done,
                                            total,
                                            resume(user, id));
                                })
                        .toList();
        long total =
                db.queryForObject(
                        "select count(distinct target_id) from entitlement where user_id=? and"
                                + " entitlement_type='COURSE_VIEW'",
                        Long.class,
                        user);
        return new PageResult<>(items, p.page(), p.pageSize(), total);
    }

    private record CourseSummary(long courseId, String productId, String title, int completed, int total) {}
    private record OwnedCourse(CourseSummary summary, EntitlementAccess.Right right) {}

    private PageResult<CourseView> filteredCourses(long user, PageQuery p, LearningFilter filter) {
        // Compute enabled chapter counts for all owned candidates BEFORE slicing. Rights come
        // from the same authority as playback, including revocations and multiple sources.
        var summaries = db.query(
                "select c.id,c.product_id,p.title,count(ch.id) total,"
                    + " coalesce(sum(case when ch.duration_seconds>0 and l.position_seconds>=ch.duration_seconds then 1 else 0 end),0) done"
                    + " from course c join product p on p.id=c.product_id"
                    + " join (select distinct target_id from entitlement where user_id=? and entitlement_type='COURSE_VIEW') e on e.target_id=c.id"
                    + " left join course_chapter ch on ch.course_id=c.id and ch.enabled=true"
                    + " left join learning_progress l on l.chapter_id=ch.id and l.user_id=?"
                    + " group by c.id,c.product_id,p.title order by c.id desc",
                (r, n) -> new CourseSummary(r.getLong("id"), r.getString("product_id"),
                        r.getString("title"), r.getInt("done"), r.getInt("total")), user, user);
        var matching = summaries.stream()
                .map(summary -> new OwnedCourse(summary, access.course(user, summary.courseId())))
                .filter(owned -> filter.includes(owned.right(), owned.summary().completed(), owned.summary().total()))
                .toList();
        var items = matching.stream().skip(p.offset()).limit(p.pageSize())
                .map(owned -> {
                    var summary = owned.summary();
                    var right = owned.right();
                    return new CourseView(Long.toString(summary.courseId()), summary.productId(), summary.title(),
                            right.status(), right.expiresAt(), summary.completed(), summary.total(),
                            resume(user, summary.courseId()));
                }).toList();
        return new PageResult<>(items, p.page(), p.pageSize(), matching.size());
    }

    private Resume resume(long user, long course) {
        var rows = db.query(
            "select c.id,c.title,least(l.position_seconds,c.duration_seconds),l.last_learned_at"
                + " from learning_progress l join course_chapter c on c.id=l.chapter_id"
                + " where l.user_id=? and c.course_id=? and c.enabled=true"
                + " order by l.last_learned_at desc,c.id desc limit 1",
            (r,n) -> new Resume(r.getString(1), r.getString(2), r.getInt(3), r.getTimestamp(4).toInstant()),
            user, course);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
