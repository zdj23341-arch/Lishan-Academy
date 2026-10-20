package com.wechat.jxsc.analytics;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.TaskQueue;
import com.wechat.jxsc.distribution.FinanceStore;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class ExportService {
    private final FinanceStore f;
    private final AnalyticsService analytics;
    private final SensitiveCipher cipher;
    private final CommerceSupport s;
    private final TaskQueue tasks;

    public ExportService(
            FinanceStore f,
            AnalyticsService analytics,
            SensitiveCipher cipher,
            CommerceSupport s,
            TaskQueue tasks) {
        this.f = f;
        this.analytics = analytics;
        this.cipher = cipher;
        this.s = s;
        this.tasks = tasks;
    }

    private static String permission(String type) {
        return switch (type) {
            case "ANALYTICS" -> "ANALYTICS_READ";
            case "ORDERS" -> "ORDER_OPERATE";
            case "COMMISSIONS" -> "DISTRIBUTION_OPERATE";
            case "WITHDRAWALS" -> "FINANCE_OPERATE";
            default -> throw CommerceSupport.invalid("Unknown export type");
        };
    }

    private void authorize(String type) {
        var a = CurrentActor.require();
        a.requireType(CurrentActor.Type.ADMIN);
        if (!a.permissions().contains("EXPORT_READ") || !a.permissions().contains(permission(type)))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Export permission required");
    }

    private boolean stillAuthorized(long user, String type) {
        return f.db.queryForObject(
                        "select count(distinct p.code) from admin_user u join admin_user_role ur on"
                            + " ur.admin_user_id=u.id join admin_role_permission rp on"
                            + " rp.role_id=ur.role_id join admin_permission p on"
                            + " p.id=rp.permission_id where u.id=? and u.status='ACTIVE' and"
                            + " u.password_change_required=false and p.code in ('EXPORT_READ',?)",
                        Long.class,
                        user,
                        permission(type))
                == 2;
    }

    @Transactional
    public AnalyticsDtos.ExportView create(AnalyticsDtos.ExportRequest r) {
        authorize(r.type());
        analytics.range(r.startDate(), r.endDate());
        long id =
                f.insert(
                        "insert into"
                            + " admin_export(requester_id,export_type,start_date,end_date,status,created_at,updated_at)"
                            + " values(?,?,?,?,'REQUESTED',?,?)",
                        CurrentActor.require().id(),
                        r.type(),
                        r.startDate(),
                        r.endDate(),
                        f.now(),
                        f.now());
        tasks.enqueue("ADMIN_EXPORT", "" + id, "{}", f.clock.instant());
        s.audit(
                "EXPORT_REQUEST",
                "EXPORT",
                id,
                "Private role-scoped export",
                Map.of("type", r.type()));
        return view(f.one("select * from admin_export where id=?", id));
    }

    private Map<String, Object> owned(long id) {
        var row =
                f.one(
                        "select * from admin_export where id=? and requester_id=?",
                        id,
                        CurrentActor.require().id());
        authorize(str(row, "export_type"));
        return row;
    }

    public AnalyticsDtos.ExportView get(long id) {
        return view(owned(id));
    }

    private AnalyticsDtos.ExportView view(Map<String, Object> r) {
        boolean usable =
                str(r, "status").equals("SUCCEEDED")
                        && time(r, "expires_at").isAfter(f.clock.instant());
        return new AnalyticsDtos.ExportView(
                "" + n(r, "id"),
                str(r, "export_type"),
                str(r, "status"),
                (int) n(r, "row_count"),
                time(r, "expires_at"),
                usable ? "/api/v1/admin/exports/" + n(r, "id") + "/content" : null,
                str(r, "error_code"));
    }

    public byte[] content(long id) {
        var row = owned(id);
        if (!str(row, "status").equals("SUCCEEDED")
                || !time(row, "expires_at").isAfter(f.clock.instant()))
            throw CommerceSupport.missing();
        return cipher.decrypt(str(row, "content_cipher")).getBytes(StandardCharsets.UTF_8);
    }

    public record Generated(long id, String csv, int rows) {}

    public Generated generate(long id) {
        var row = f.one("select * from admin_export where id=?", id);
        if (str(row, "status").equals("SUCCEEDED")) return null;
        String type = str(row, "export_type");
        if (!stillAuthorized(n(row, "requester_id"), type))
            throw new IllegalStateException("EXPORT_PERMISSION_REVOKED");
        LocalDate start = ((java.sql.Date) row.get("start_date")).toLocalDate(),
                end = ((java.sql.Date) row.get("end_date")).toLocalDate();
        var range = analytics.range(start, end);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (type.equals("ANALYTICS")) {
            for (var metric : analytics.trend(start, end).items()) {
                Map<String, Object> values =
                        s.mapper()
                                .convertValue(
                                        metric,
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                LinkedHashMap<String, Object>>() {});
                // CSV keeps scalar columns; nested API aliases duplicate these existing values.
                values.keySet().removeAll(List.of("period", "periodMetrics", "currentMetrics"));
                rows.add(values);
            }
        } else {
            String sql =
                    switch (type) {
                        case "ORDERS" ->
                                "select"
                                    + " o.id,o.order_no,o.order_status,o.goods_amount_fen,o.freight_amount_fen,o.paid_at"
                                    + " from store_order o where o.channel<>'DEV' and"
                                    + " o.created_at>=? and o.created_at<? order by o.id limit"
                                    + " 10001";
                        case "COMMISSIONS" ->
                                "select"
                                    + " c.id,c.order_id,c.distributor_id,c.earned_fen,c.reversed_fen,c.state"
                                    + " from commission_ledger c join store_order o on"
                                    + " o.id=c.order_id where o.channel<>'DEV' and c.created_at>=?"
                                    + " and c.created_at<? order by c.id limit 10001";
                        default ->
                                "select id,distributor_id,amount_fen,status,out_bill_no,created_at"
                                        + " from withdrawal_request where channel<>'DEV' and"
                                        + " created_at>=? and created_at<? order by id limit 10001";
                    };
            rows = f.db.queryForList(sql, ts(range.from()), ts(range.until()));
        }
        if (rows.size() > 10000) throw new IllegalStateException("EXPORT_ROW_LIMIT");
        StringBuilder csv = new StringBuilder();
        if (rows.isEmpty()) csv.append("No records\r\n");
        else {
            csv.append(String.join(",", rows.getFirst().keySet())).append("\r\n");
            for (var r : rows) {
                List<String> values = new ArrayList<>();
                for (Object value : r.values()) {
                    String text = value == null ? "" : value.toString();
                    if (text.matches("^[=+@-].*")) text = "'" + text;
                    values.add("\"" + text.replace("\"", "\"\"") + "\"");
                }
                csv.append(String.join(",", values)).append("\r\n");
                if (csv.length() > 1048576) throw new IllegalStateException("EXPORT_SIZE_LIMIT");
            }
        }
        if (csv.toString().getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalStateException("EXPORT_SIZE_LIMIT");
        return new Generated(id, csv.toString(), rows.size());
    }

    @Transactional
    public void save(Generated g) {
        if (g == null) return;
        var row = f.one("select * from admin_export where id=? for update", g.id());
        if (str(row, "status").equals("SUCCEEDED")) return;
        f.db.update(
                "update admin_export set"
                    + " status='SUCCEEDED',content_cipher=?,row_count=?,expires_at=?,error_code=null,updated_at=?"
                    + " where id=?",
                cipher.encrypt(g.csv()),
                g.rows(),
                ts(f.clock.instant().plusSeconds(900)),
                f.now(),
                g.id());
        s.systemAudit(
                "EXPORT_COMPLETE",
                "EXPORT",
                g.id(),
                "Private export generated",
                Map.of("rows", g.rows()));
    }

    @Transactional
    public void fail(long id) {
        f.db.update(
                "update admin_export set status='FAILED',error_code='EXPORT_FAILED',updated_at=?"
                        + " where id=? and status<>'SUCCEEDED'",
                f.now(),
                id);
        s.systemAudit("EXPORT_FAILED", "EXPORT", id, "Export generation failed", Map.of());
    }
}
