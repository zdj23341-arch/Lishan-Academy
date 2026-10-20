package com.wechat.jxsc.membership;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Directory status uses live projected intervals, never a cached record expiry or order status. */
public final class MembershipQuery {
    public enum Status { NONE, ACTIVE, EXPIRED, PENDING }
    private MembershipQuery() {}

    public record Filter(String sql, List<Object> parameters) {}

    public static Filter filter(Long userId, Status status, Instant now) {
        String where = " where 1=1";
        List<Object> parameters = new ArrayList<>();
        if (userId != null) {
            where += " and m.user_id=?";
            parameters.add(userId);
        }
        if (status != null) {
            where += " and (case"
                + " when exists(select 1 from membership_interval i where i.user_id=m.user_id and i.starts_at<=? and i.expires_at>?) then 'ACTIVE'"
                + " when exists(select 1 from membership_interval i where i.user_id=m.user_id and i.starts_at>?) then 'PENDING'"
                + " when exists(select 1 from membership_interval i where i.user_id=m.user_id) then 'EXPIRED'"
                + " else 'NONE' end)=?";
            parameters.add(Timestamp.from(now));
            parameters.add(Timestamp.from(now));
            parameters.add(Timestamp.from(now));
            parameters.add(status.name());
        }
        return new Filter(where, parameters);
    }
}
