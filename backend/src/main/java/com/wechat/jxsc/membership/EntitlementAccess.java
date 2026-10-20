package com.wechat.jxsc.membership;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** The single authority for metadata flags and content/learning access. No order locks or I/O. */
@Service
@Transactional(readOnly = true)
public class EntitlementAccess {
    private final JdbcTemplate db;
    private final Clock clock;

    public EntitlementAccess(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Right(String status,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}, format = "date-time") Instant startsAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}, format = "date-time") Instant expiresAt) {
        public boolean active() {
            return status.equals("ACTIVE");
        }
    }

    public record Chapter(
            long id,
            long courseId,
            Long assetId,
            int durationSeconds,
            boolean trial,
            boolean enabled,
            boolean published) {}

    public static Long viewer() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                        && auth.getPrincipal() instanceof CurrentActor actor
                        && actor.type() == CurrentActor.Type.CUSTOMER
                ? actor.id()
                : null;
    }

    public static long customer() {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        return actor.id();
    }

    public Right course(Long user, long course) {
        return course(user, course, false);
    }

    public Right course(Long user, long course, boolean current) {
        return course(user, course, current, null);
    }

    public Right course(Long user, long course, boolean current, String excludedSource) {
        if (user == null) return new Right("NONE", null, null);
        // Current reads query both grants and revocations: locking the mutex cannot refresh an RR
        // snapshot.
        String suffix = current ? " for update" : "";
        var revoked =
                new HashSet<>(
                        db.queryForList(
                                "select source_key from entitlement_event where user_id=? and"
                                        + " event_type='REVOKE'"
                                        + suffix,
                                String.class,
                                user));
        if (excludedSource != null) revoked.add(excludedSource);
        var rights =
                db.query(
                        "select source_key,starts_at,expires_at from entitlement where user_id=?"
                                + " and entitlement_type='COURSE_VIEW' and target_id=? order by"
                                + " paid_at,id"
                                + suffix,
                        (r, n) ->
                                revoked.contains(r.getString("source_key"))
                                        ? null
                                        : new Right(
                                                "GRANTED",
                                                r.getTimestamp("starts_at").toInstant(),
                                                r.getTimestamp("expires_at") == null
                                                        ? null
                                                        : r.getTimestamp("expires_at").toInstant()),
                        user,
                        course);
        Right last = null;
        for (var right : rights) {
            if (right == null) continue;
            if (!right.startsAt().isAfter(clock.instant())
                    && (right.expiresAt() == null || clock.instant().isBefore(right.expiresAt())))
                return new Right("ACTIVE", right.startsAt(), right.expiresAt());
            last = right;
        }
        return last == null
                ? new Right("NONE", null, null)
                : new Right(
                        last.startsAt().isAfter(clock.instant()) ? "PENDING" : "EXPIRED",
                        last.startsAt(),
                        last.expiresAt());
    }

    public Right membership(Long user) {
        return membership(user, false);
    }

    public Right membership(Long user, boolean current) {
        return membershipAt(user, current, clock.instant());
    }

    public Right membershipAt(Long user, boolean current, Instant now) {
        if (user == null) return new Right("NONE", null, null);
        var intervals =
                db.query(
                        "select starts_at,expires_at from membership_interval where user_id=? order"
                                + " by starts_at,grant_id"
                                + (current ? " for update" : ""),
                        (r, n) ->
                                new Right(
                                        "GRANTED",
                                        r.getTimestamp(1).toInstant(),
                                        r.getTimestamp(2).toInstant()),
                        user);
        Instant start = null, end = null;
        List<Right> chains = new ArrayList<>();
        for (var v : intervals) {
            if (end == null || v.startsAt().isAfter(end)) {
                if (end != null) chains.add(new Right("GRANTED", start, end));
                start = v.startsAt();
            }
            end = v.expiresAt();
        }
        if (end != null) chains.add(new Right("GRANTED", start, end));
        for (var chain : chains)
            if (!chain.startsAt().isAfter(now)
                    && now.isBefore(chain.expiresAt()))
                return new Right("ACTIVE", chain.startsAt(), chain.expiresAt());
        if (chains.isEmpty()) return new Right("NONE", null, null);
        var latest = chains.getLast();
        return new Right(
                latest.startsAt().isAfter(now) ? "PENDING" : "EXPIRED",
                latest.startsAt(),
                latest.expiresAt());
    }

    public Chapter chapter(long course, long chapter) {
        return chapter(course, chapter, false);
    }

    /** Locks only the chapter, never catalog product/course rows after the subject mutex. */
    public Chapter chapter(long course, long chapter, boolean current) {
        boolean published =
                db.queryForObject(
                                "select count(*) from course c join product p on p.id=c.product_id"
                                    + " where c.id=? and p.status<>'DRAFT'",
                                Integer.class,
                                course)
                        == 1;
        var rows =
                db.query(
                        "select"
                            + " ch.id,ch.course_id,ch.video_asset_id,ch.duration_seconds,ch.is_trial,ch.enabled"
                            + " from course_chapter ch where ch.id=? and ch.course_id=?"
                                + (current ? " for share" : ""),
                        (r, n) ->
                                new Chapter(
                                        r.getLong(1),
                                        r.getLong(2),
                                        r.getObject(3, Long.class),
                                        r.getInt(4),
                                        r.getBoolean(5),
                                        r.getBoolean(6),
                                        published),
                        chapter,
                        course);
        if (rows.isEmpty() || !rows.getFirst().published()) throw missing();
        return rows.getFirst();
    }

    public Chapter chapter(long chapter) {
        return chapter(chapter, false);
    }

    public Chapter chapter(long chapter, boolean current) {
        var courses =
                db.queryForList(
                        "select course_id from course_chapter where id=?", Long.class, chapter);
        if (courses.isEmpty()) throw missing();
        return chapter(courses.getFirst(), chapter, current);
    }

    public boolean videoReady(Chapter c) {
        return c.enabled()
                && c.assetId() != null
                && db.queryForObject(
                                "select count(*) from file_asset where id=? and"
                                        + " purpose='COURSE_VIDEO' and status='READY'",
                                Integer.class,
                                c.assetId())
                        == 1;
    }

    public boolean canPlay(Long user, long course, long chapter) {
        var c = chapter(course, chapter);
        return videoReady(c) && (c.trial() || course(user, course).active());
    }

    public Right playback(Long user, Chapter chapter, boolean current) {
        if (!chapter.enabled()) throw contentUnavailable();
        var right = course(user, chapter.courseId(), current);
        if (!chapter.trial()) require(right, "Purchase this course to play");
        if (!videoReady(chapter)) throw contentUnavailable();
        return chapter.trial() ? new Right("ACTIVE", null, null) : right;
    }

    public void paperRelation(long paper, long file) {
        paperRelation(paper, file, false);
    }

    public void paperRelation(long paper, long file, boolean current) {
        String suffix = current ? " for share" : "";
        // Paper -> attachment matches the metadata writer's order. These are unrelated to checkout
        // catalog locks.
        if (db.queryForList(
                                "select id from paper where id=? and status='PUBLISHED' and"
                                    + " visibility='MEMBER'"
                                        + suffix,
                                Long.class,
                                paper)
                        .isEmpty()
                || db.queryForList(
                                "select id from paper_attachment where paper_id=? and asset_id=?"
                                        + suffix,
                                Long.class,
                                paper,
                                file)
                        .isEmpty()) throw missing();
    }

    public boolean canAccessPaper(Long user) {
        return membership(user).active();
    }

    public int position(Long user, long chapter) {
        if (user == null) return 0;
        var values =
                db.queryForList(
                        "select least(p.position_seconds,c.duration_seconds) from learning_progress"
                            + " p join course_chapter c on c.id=p.chapter_id where p.user_id=? and"
                            + " p.chapter_id=?",
                        Integer.class,
                        user,
                        chapter);
        return values.isEmpty() ? 0 : values.getFirst();
    }

    public static void require(Right right, String message) {
        if (!right.active())
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    right.status().equals("EXPIRED") ? "ENTITLEMENT_EXPIRED" : "FORBIDDEN",
                    message);
    }

    public static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    public static ApiException contentUnavailable() {
        return new ApiException(
                HttpStatus.CONFLICT, "FILE_NOT_READY", "Chapter or file is not ready");
    }
}
