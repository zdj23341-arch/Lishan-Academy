package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;
import com.wechat.jxsc.membership.EntitlementAccess;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.context.request.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

@Service
public class AdminUsersService {
    @PersistenceContext private EntityManager em;
    private final JdbcTemplate db;
    private final EntitlementAccess access;
    private final OperationAuditService audit;
    private final Clock clock;

    public AdminUsersService(JdbcTemplate db, EntitlementAccess access, OperationAuditService audit, Clock clock) {
        this.db = db; this.access = access; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AdminUserDtos.View> list(PageQuery p, String keyword, AdminUserDtos.Status status) {
        Instant now = clock.instant();
        String where = " where 1=1";
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.strip();
            if (value.length() > 200) throw ContentSupport.invalid("Keyword is too long");
            // LOCATE treats %, _ and quotes literally; numeric ID matching is exact.
            if (value.matches("[1-9][0-9]{0,19}")) {
                where += " and cast(u.id as char)=?";
                args.add(value);
            } else {
                where += " and locate(?,u.nickname)>0";
                args.add(value);
            }
        }
        if (status != null) {
            where += " and u.status=?";
            args.add(switch(status) { case NORMAL -> "ACTIVE"; case BLOCKED -> "DISABLED"; case CLOSED -> "ANONYMIZED"; });
        }
        long total = db.queryForObject("select count(*) from user_account u" + where, Long.class, args.toArray());
        args.add(p.pageSize()); args.add(p.offset());
        var rows = db.query("select u.*,(select max(w.last_login_at) from user_wechat_identity w where w.user_id=u.id) as last_login_at"
                + " from user_account u" + where + " order by u.id desc limit ? offset ?",
                (r,n) -> new AdminUserDtos.View(r.getString("id"), null, r.getString("nickname"), null,
                        status(r.getString("status")), r.getTimestamp("created_at").toInstant(),
                        r.getTimestamp("last_login_at") == null ? null : r.getTimestamp("last_login_at").toInstant(),
                        r.getLong("version"), access.membershipAt(r.getLong("id"), false, now)), args.toArray());
        return new PageResult<>(rows, p.page(), p.pageSize(), total);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminUserDtos.View status(long id, AdminUserDtos.StatusWrite request) {
        if (id <= 0) throw ContentSupport.invalid("Invalid user ID");
        // Same lock ordering as admission and closure. A disabled account retains its lifecycle;
        // CLOSED is irreversible and never converted back to ACTIVE by this operation.
        var states = db.queryForList("select state from account_lifecycle where user_id=? for update", String.class, id);
        if (states.isEmpty()) throw ContentSupport.missing();
        if (!states.getFirst().equals("ACTIVE")) throw closed();
        var user = em.find(UserAccount.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) throw ContentSupport.missing();
        if (user.status.equals("ANONYMIZED")) throw closed();
        ContentSupport.version(user.getVersion(), request.version());
        var before = Map.of("status", status(user.status).name(), "version", user.getVersion());
        String target = request.status() == AdminUserDtos.WritableStatus.NORMAL ? "ACTIVE" : "DISABLED";
        if (!user.status.equals(target)) {
            user.status = target;
            em.flush();
            if (target.equals("DISABLED")) db.update("update login_session set revoked_at=? where actor_type='CUSTOMER' and actor_id=? and revoked_at is null",
                    Timestamp.from(clock.instant()), id);
            var attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            audit.append(CurrentActor.require(), "USER_STATUS_CHANGE", "USER", Long.toString(id), before,
                    Map.of("status", status(target).name(), "version", user.getVersion()), request.reason(),
                    attributes == null ? "USER-STATUS" : RequestIdFilter.requestId(attributes.getRequest()));
        }
        var last = db.queryForObject("select max(last_login_at) from user_wechat_identity where user_id=?", Timestamp.class, id);
        return new AdminUserDtos.View(Long.toString(id), null, user.nickname, null, status(user.status), user.getCreatedAt(),
                last == null ? null : last.toInstant(), user.getVersion(), access.membership(id));
    }

    private static AdminUserDtos.Status status(String value) {
        return switch(value) { case "ACTIVE" -> AdminUserDtos.Status.NORMAL; case "DISABLED" -> AdminUserDtos.Status.BLOCKED;
            case "ANONYMIZED" -> AdminUserDtos.Status.CLOSED; default -> throw new IllegalStateException("Unknown user state"); };
    }

    private static ApiException closed() {
        return new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED", "Closed accounts cannot be restored");
    }
}
