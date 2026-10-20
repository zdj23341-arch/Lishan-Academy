package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.membership.EntitlementAccess;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Customer-facing read model over the immutable {@code download_audit} ledger. The audit stays the
 * record of truth; this only exposes the caller's own rows, one per paper file, newest first.
 */
@Service
public class PaperDownloadService {
    static final int MAX_LIMIT = 50;

    private final JdbcTemplate db;
    private final EntitlementAccess access;

    public PaperDownloadService(JdbcTemplate db, EntitlementAccess access) {
        this.db = db;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<PaperDtos.Download> recent(int limit) {
        if (limit < 1 || limit > MAX_LIMIT)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid download history limit");
        long user = EntitlementAccess.customer();
        boolean member = access.membership(user).active();
        // Window functions keep one row per (paper,file) while preserving the latest action/time and
        // the total count, so re-downloading the same file does not flood the list.
        String sql =
                """
                select t.paper_id,t.asset_id,t.action,t.created_at,t.times,
                       p.title as paper_title,p.status as paper_status,
                       a.original_name,a.extension,a.mime,a.bytes,a.status as asset_status,
                       (select count(*) from paper_attachment pa
                         where pa.paper_id=t.paper_id and pa.asset_id=t.asset_id) as attached
                  from (select d.paper_id,d.asset_id,d.action,d.created_at,
                               row_number() over (partition by d.paper_id,d.asset_id
                                                  order by d.created_at desc,d.id desc) as rn,
                               count(*) over (partition by d.paper_id,d.asset_id) as times
                          from download_audit d
                         where d.user_id=?) t
                  left join paper p on p.id=t.paper_id
                  left join file_asset a on a.id=t.asset_id
                 where t.rn=1
                 order by t.created_at desc
                 limit ?
                """;
        return db.query(
                sql,
                (r, n) -> {
                    String paperTitle = r.getString("paper_title");
                    String paperStatus = r.getString("paper_status");
                    String assetStatus = r.getString("asset_status");
                    String mime = r.getString("mime");
                    boolean attached = r.getInt("attached") > 0;
                    boolean ready = "READY".equals(assetStatus) && mime != null;
                    String reason = null;
                    if (!member) reason = "MEMBERSHIP_REQUIRED";
                    else if (!"PUBLISHED".equals(paperStatus)) reason = "PAPER_UNAVAILABLE";
                    else if (!attached) reason = "FILE_REMOVED";
                    else if (!ready) reason = "FILE_NOT_READY";
                    return new PaperDtos.Download(
                            Long.toString(r.getLong("paper_id")),
                            paperTitle == null ? "已下架资料" : paperTitle,
                            paperStatus == null ? "UNKNOWN" : paperStatus,
                            Long.toString(r.getLong("asset_id")),
                            r.getString("original_name") == null
                                    ? "历史文件"
                                    : r.getString("original_name"),
                            r.getString("extension") == null ? "" : r.getString("extension"),
                            mime == null ? "" : mime,
                            r.getObject("bytes") == null ? 0L : r.getLong("bytes"),
                            r.getString("action"),
                            toInstant(r.getTimestamp("created_at")),
                            r.getInt("times"),
                            reason == null,
                            reason);
                },
                user,
                limit);
    }

    private static Instant toInstant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
