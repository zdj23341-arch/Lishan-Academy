package com.wechat.jxsc.membership;

import com.wechat.jxsc.common.commerce.CommerceSupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class EntitlementLedger {
    private final JdbcTemplate db;
    private final CommerceSupport support;

    public EntitlementLedger(JdbcTemplate db, CommerceSupport support) {
        this.db = db;
        this.support = support;
    }

    public static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** Must be called with the customer lock, before taking any order lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureSubject(long user) {
        db.update(
                "insert into entitlement_subject(user_id) values (?) on duplicate key update"
                        + " user_id=values(user_id)",
                user);
    }

    /**
     * All write/current reads follow order (if any) -> subject; no later order/customer/catalog
     * lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long user) {
        if (db.queryForList(
                        "select user_id from entitlement_subject where user_id=? for update",
                        Long.class,
                        user)
                .isEmpty())
            throw new IllegalStateException("Entitlement subject must exist before order delivery");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grant(
            long user,
            Long item,
            String source,
            Long course,
            Instant paid,
            Integer days,
            Integer months) {
        lock(user);
        if (!db.queryForList(
                                "select id from entitlement where source_key=? for update",
                                Long.class,
                                source)
                        .isEmpty()
                || revoked(source)) return;
        if (!db.queryForList(
                        "select user_id from account_closure where user_id=? for update",
                        Long.class,
                        user)
                .isEmpty()) {
            event(
                    user,
                    item,
                    source,
                    "close:" + source,
                    "REVOKE",
                    Map.of("reason", "ACCOUNT_CLOSURE_NO_REFUND"));
            return;
        }
        Instant start = paid, end;
        if (months != null) {
            var prior = replay(user, null);
            // The original interval is the interval assigned when delivered; out-of-order delivery
            // later changes only derived intervals, never this immutable receipt.
            if (!prior.isEmpty() && prior.getLast().expiresAt().isAfter(start))
                start = prior.getLast().expiresAt();
            end = MembershipReplay.plusMonths(start, months);
        } else end = days == null ? null : paid.plus(Duration.ofDays(days));
        db.update(
                "insert into"
                    + " entitlement(user_id,order_item_id,source_key,entitlement_type,target_id,paid_at,starts_at,expires_at)"
                    + " values (?,?,?,?,?,?,?,?)",
                user,
                item,
                source,
                months == null ? "COURSE_VIEW" : "MEMBER_PAPER",
                course,
                time(paid),
                time(start),
                time(end));
        long entitlement =
                db.queryForObject(
                        "select id from entitlement where source_key=? for update",
                        Long.class,
                        source);
        if (months != null)
            db.update(
                    "insert into"
                        + " membership_grant(entitlement_id,user_id,order_item_id,paid_at,duration_months,original_start_at,original_end_at)"
                        + " values (?,?,?,?,?,?,?)",
                    entitlement,
                    user,
                    item,
                    time(paid),
                    months,
                    time(start),
                    time(end));
        event(
                user,
                item,
                source,
                "grant:" + source,
                "GRANT",
                Map.of("entitlementId", Long.toString(entitlement)));
        if (months != null) project(user, source, item);
        support.systemAudit(
                "ENTITLEMENT_GRANTED",
                "ENTITLEMENT",
                entitlement,
                "Purchased snapshot or explicit DEV grant",
                Map.of("source", source, "type", months == null ? "COURSE_VIEW" : "MEMBER_PAPER"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(long user, long item, String source, boolean membership) {
        lock(user);
        if (revoked(source)) return;
        event(
                user,
                item,
                source,
                "revoke:" + source,
                "REVOKE",
                Map.of("reason", "FULL_GOODS_REFUND"));
        if (membership) project(user, source, item);
        support.systemAudit(
                "ENTITLEMENT_REVOKED",
                "ORDER_ITEM",
                item,
                "Confirmed full goods refund",
                Map.of("source", source));
    }

    private boolean revoked(String source) {
        return !db.queryForList(
                        "select id from entitlement_event where source_key=? and"
                                + " event_type='REVOKE' for update",
                        Long.class,
                        source)
                .isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void forfeit(long user) {
        lock(user);
        for (var row :
                db.queryForList(
                        "select source_key,order_item_id from entitlement where user_id=? order by"
                                + " id for update",
                        user)) {
            String source = (String) row.get("source_key");
            Number item = (Number) row.get("order_item_id");
            if (!revoked(source))
                event(
                        user,
                        item == null ? null : item.longValue(),
                        source,
                        "close:" + source,
                        "REVOKE",
                        Map.of("reason", "ACCOUNT_CLOSURE_NO_REFUND"));
        }
        project(user, "account-close:" + user, null);
    }

    /**
     * Current locking reads are essential even when the caller already established an RR snapshot.
     */
    public List<MembershipReplay.Interval> replay(long user, String exclude) {
        return MembershipReplay.replay(membershipGrants(user, exclude, true));
    }

    /** Read-only offer projections use the same surviving grants in one consistent snapshot. */
    @Transactional(readOnly = true)
    public List<MembershipReplay.Grant> membershipGrants(long user) {
        return membershipGrants(user, null, false);
    }

    private List<MembershipReplay.Grant> membershipGrants(
            long user, String exclude, boolean current) {
        String suffix = current ? " for update" : "";
        var revoked =
                new HashSet<>(
                        db.queryForList(
                                "select source_key from entitlement_event where user_id=? and"
                                        + " event_type='REVOKE'"
                                        + suffix,
                                String.class,
                                user));
        if (exclude != null) revoked.add(exclude);
        var grants =
                db.query(
                        "select g.id,g.paid_at,g.duration_months,e.source_key from membership_grant"
                                + " g join entitlement e on e.id=g.entitlement_id where g.user_id=?"
                                + " order by g.paid_at,g.id"
                                + suffix,
                        (r, n) ->
                                revoked.contains(r.getString("source_key"))
                                        ? null
                                        : new MembershipReplay.Grant(
                                                r.getLong("id"),
                                                r.getTimestamp("paid_at").toInstant(),
                                                r.getInt("duration_months")),
                        user);
        return grants.stream().filter(Objects::nonNull).toList();
    }

    private void project(long user, String source, Long item) {
        var intervals = replay(user, null);
        db.update("delete from membership_interval where user_id=?", user);
        for (var v : intervals)
            db.update(
                    "insert into membership_interval(grant_id,user_id,starts_at,expires_at) values"
                            + " (?,?,?,?)",
                    v.grantId(),
                    user,
                    time(v.startsAt()),
                    time(v.expiresAt()));
        Instant start = null, end = null;
        for (var v : intervals) {
            if (end == null || v.startsAt().isAfter(end)) start = v.startsAt();
            end = v.expiresAt();
        }
        db.update(
                "insert into membership_record(user_id,current_start_at,expires_at) values (?,?,?)"
                    + " on duplicate key update"
                    + " current_start_at=values(current_start_at),expires_at=values(expires_at),version=version+1,updated_at=UTC_TIMESTAMP(6)",
                user,
                time(start),
                time(end));
        event(user, item, source, "projection:" + UUID.randomUUID(), "REPROJECT", intervals);
    }

    private void event(
            long user, Long item, String source, String key, String type, Object detail) {
        db.update(
                "insert into"
                    + " entitlement_event(user_id,order_item_id,source_key,event_key,event_type,detail_json)"
                    + " values (?,?,?,?,?,?)",
                user,
                item,
                source,
                key,
                type,
                support.encode(detail));
    }
}
