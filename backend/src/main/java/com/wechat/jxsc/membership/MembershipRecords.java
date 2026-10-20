package com.wechat.jxsc.membership;

import com.wechat.jxsc.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.Clock;
import java.util.ArrayList;

@Service
public class MembershipRecords {
    private final JdbcTemplate db;
    private final EntitlementAccess access;
    private final Clock clock;

    public MembershipRecords(JdbcTemplate db, EntitlementAccess access, Clock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MembershipDtos.Record> list(PageQuery p) {
        return list(p, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MembershipDtos.Record> list(PageQuery p, Long userId, MembershipQuery.Status status) {
        var now = clock.instant();
        var filter = MembershipQuery.filter(userId, status, now);
        var parameters = new ArrayList<>(filter.parameters());
        parameters.add(p.pageSize());
        parameters.add(p.offset());
        var rows =
                db.query(
                        "select m.id,m.user_id,m.version from membership_record m" + filter.sql()
                                + " order by m.id desc limit ? offset ?",
                        (r, n) -> {
                            var right = access.membershipAt(r.getLong("user_id"), false, now);
                            return new MembershipDtos.Record(
                                    r.getString("id"),
                                    r.getString("user_id"),
                                    "STANDARD",
                                    right.status(),
                                    right.startsAt(),
                                    right.expiresAt(),
                                    r.getLong("version"));
                        },
                        parameters.toArray());
        return new PageResult<>(
                rows,
                p.page(),
                p.pageSize(),
                db.queryForObject("select count(*) from membership_record m" + filter.sql(), Long.class, filter.parameters().toArray()));
    }
}
