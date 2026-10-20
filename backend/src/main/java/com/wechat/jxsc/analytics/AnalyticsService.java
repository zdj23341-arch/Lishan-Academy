package com.wechat.jxsc.analytics;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.distribution.FinanceStore;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class AnalyticsService {
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final FinanceStore f;
    private final Environment env;

    public AnalyticsService(FinanceStore f, Environment env) {
        this.f = f;
        this.env = env;
    }

    public record Range(Instant from, Instant until) {}

    public Range range(LocalDate start, LocalDate end) {
        if (start == null
                || end == null
                || end.isBefore(start)
                || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 365)
            throw CommerceSupport.invalid(
                    "Use an inclusive date range of at most 366 Shanghai days");
        return new Range(
                start.atStartOfDay(ZoneId.of(TIMEZONE)).toInstant(),
                end.plusDays(1).atStartOfDay(ZoneId.of(TIMEZONE)).toInstant());
    }

    private record Scope(String environment, boolean includesDevChannel) {
        String channel(String alias) {
            return includesDevChannel ? "1=1" : alias + ".channel<>'DEV'";
        }

        String grant() {
            return "g.order_item_id is not null and "
                    + channel("so")
                    + " and (so.channel='NO_CHARGE' or exists(select 1 from payment_transaction gp"
                    + " where gp.id=so.successful_payment_id and gp.state='SUCCEEDED' and "
                    + channel("gp")
                    + "))";
        }

        String flow() {
            return "((l.order_id is not null and exists(select 1 from store_order so where"
                    + " so.id=l.order_id and "
                    + channel("so")
                    + " and (so.channel='NO_CHARGE' or exists(select 1 from payment_transaction sp"
                    + " where sp.id=so.successful_payment_id and sp.state='SUCCEEDED' and "
                    + channel("sp")
                    + ")))) or (l.withdrawal_id is not null and exists(select 1 from"
                    + " withdrawal_request w where w.id=l.withdrawal_id and "
                    + channel("w")
                    + ")))";
        }
    }

    private Scope scope(boolean includeDev) {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        boolean test = env.acceptsProfiles(Profiles.of("test"));
        boolean dev = env.acceptsProfiles(Profiles.of("dev"));
        if (includeDev && (prod || !(test || dev)))
            throw CommerceSupport.invalid("DEV channel inclusion is only allowed in dev/test");
        return new Scope(prod ? "prod" : test ? "test" : dev ? "dev" : "prod", includeDev);
    }

    private static long safe(BigDecimal value) {
        try {
            long n = value == null ? 0 : value.longValueExact();
            if (n >= -MAX_SAFE_INTEGER && n <= MAX_SAFE_INTEGER) return n;
        } catch (ArithmeticException ignored) {
            /* Fail closed on SQL decimal/long overflow. */
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "ANALYTICS_RANGE_EXCEEDED",
                "Analytics result exceeds the safe integer range");
    }

    private static long safe(long value) {
        return safe(BigDecimal.valueOf(value));
    }

    /**
     * Request-local caches: daily aggregates use one SQL statement per metric for the whole range.
     */
    private final class Queries {
        final Range range;
        final boolean daily;
        final Instant generatedAt = f.clock.instant();
        final Map<List<Object>, Long> snapshots = new HashMap<>();
        final Map<String, Map<LocalDate, Long>> days = new HashMap<>();

        Queries(Range range, boolean daily) {
            this.range = range;
            this.daily = daily;
        }

        long sum(String sql, Object... args) {
            var key = new ArrayList<Object>();
            key.add(sql);
            key.addAll(Arrays.asList(args));
            return snapshots.computeIfAbsent(
                    key, ignored -> safe(f.db.queryForObject(sql, BigDecimal.class, args)));
        }

        long period(String sql, String eventTime, Range selected) {
            String bounded = sql + " and " + eventTime + ">=? and " + eventTime + "<?";
            if (!daily) return sum(bounded, ts(selected.from()), ts(selected.until()));
            var values =
                    days.computeIfAbsent(
                            bounded,
                            ignored -> {
                                // All event timestamps are stored in UTC; +08:00 has no daylight
                                // saving transitions.
                                String date = "date(date_add(" + eventTime + ", interval 8 hour))";
                                int from = sql.indexOf(" from ");
                                String grouped =
                                        bounded.substring(0, from)
                                                + ", "
                                                + date
                                                + " as metric_date"
                                                + bounded.substring(from)
                                                + " group by "
                                                + date;
                                Map<LocalDate, Long> result = new HashMap<>();
                                f.db.query(
                                        grouped,
                                        rs -> {
                                            result.put(
                                                    rs.getDate("metric_date").toLocalDate(),
                                                    safe(rs.getBigDecimal(1)));
                                        },
                                        ts(range.from()),
                                        ts(range.until()));
                                return result;
                            });
            return values.getOrDefault(
                    selected.from().atZone(ZoneId.of(TIMEZONE)).toLocalDate(), 0L);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsDtos.Overview overview(LocalDate start, LocalDate end) {
        return overview(start, end, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsDtos.Overview overview(LocalDate start, LocalDate end, boolean includeDev) {
        var r = range(start, end);
        return overview(start, end, scope(includeDev), new Queries(r, false));
    }

    private AnalyticsDtos.Overview overview(
            LocalDate start, LocalDate end, Scope scope, Queries q) {
        Range r = range(start, end);
        String real = scope.channel("p") + " and " + scope.channel("o");
        String paid =
                " from payment_transaction p join store_order o on o.id=p.order_id where"
                        + " p.state='SUCCEEDED' and "
                        + real;
        String sold =
                " from store_order o join payment_transaction p on p.id=o.successful_payment_id"
                        + " join order_item i on i.order_id=o.id where p.state='SUCCEEDED' and "
                        + real;
        String refunded =
                " from refund_transaction r join payment_transaction p on p.id=r.payment_id"
                        + " join store_order o on o.id=r.order_id where r.state='SUCCEEDED' and "
                        + scope.channel("r")
                        + " and "
                        + real;
        // Corrections remain visible on their immutable posting date, even for retained FAILED
        // records.
        String corrected =
                " from economic_resolution e join refund_transaction r on r.id=e.transaction_id"
                        + " join payment_transaction p on p.id=r.payment_id join store_order o on"
                        + " o.id=e.order_id where e.resource_type='REFUND' and "
                        + scope.channel("r")
                        + " and "
                        + real;
        String prior =
                "exists(select 1 from membership_grant g join order_item si on"
                        + " si.id=g.order_item_id join store_order so on so.id=si.order_id where"
                        + " g.user_id=o.user_id and "
                        + scope.grant()
                        + " and (g.paid_at<p.paid_at or (g.paid_at=p.paid_at and so.id<o.id)))";
        String member =
                " and i.type='MEMBERSHIP' and exists(select 1 from membership_grant delivered"
                        + " where delivered.order_item_id=i.id) and ";
        String download =
                " from download_audit d join download_provenance dp on dp.audit_id=d.id"
                        + " where d.result='AUTHORIZED' and dp.source_kind in "
                        + (scope.includesDevChannel()
                                ? "('NON_DEV_PAYMENT','NO_CHARGE','DEV_PAYMENT','DEV_SEED')"
                                : "('NON_DEV_PAYMENT','NO_CHARGE')");
        String flow = " from commission_account_ledger l where " + scope.flow();
        String shipment =
                " from store_order o join order_item i on i.order_id=o.id"
                        + " left join payment_transaction p on p.id=o.successful_payment_id where "
                        + scope.channel("o")
                        + " and (o.channel='NO_CHARGE' or (p.state='SUCCEEDED' and "
                        + scope.channel("p")
                        + ")) and o.fulfillment_status='PENDING_SHIPMENT' and i.type='BOOK'"
                        + " and o.paid_at is not null and o.refund_status<>'FULL'"
                        + " and i.quantity>i.restored_quantity";
        long receipts = q.period("select coalesce(sum(p.amount_fen),0)" + paid, "p.paid_at", r);
        long refunds =
                safe(
                        q.period(
                                        "select coalesce(sum(r.goods_fen+r.freight_fen),0)"
                                                + refunded,
                                        "r.success_at",
                                        r)
                                + q.period(
                                        "select coalesce(sum(e.observed_fen),0)" + corrected,
                                        "e.created_at",
                                        r));
        return new AnalyticsDtos.Overview(
                start,
                end,
                TIMEZONE,
                q.generatedAt,
                q.period("select count(distinct o.id)" + paid, "p.paid_at", r),
                q.period("select count(distinct o.user_id)" + paid, "p.paid_at", r),
                receipts,
                refunds,
                safe(receipts - refunds),
                q.period(
                        "select coalesce(sum(i.quantity),0)" + sold + " and i.type='COURSE'",
                        "p.paid_at",
                        r),
                q.period(
                        "select coalesce(sum(i.quantity),0)" + sold + " and i.type='BOOK'",
                        "p.paid_at",
                        r),
                safe(
                        q.period(
                                        "select coalesce(sum(r.goods_fen),0)" + refunded,
                                        "r.success_at",
                                        r)
                                + q.period(
                                        "select coalesce(sum(e.goods_fen),0)" + corrected,
                                        "e.created_at",
                                        r)),
                q.period(
                        "select coalesce(sum(a.received_quantity),0) from aftersale_case a join"
                                + " store_order o on o.id=a.order_id join payment_transaction p on"
                                + " p.id=o.successful_payment_id where "
                                + real,
                        "a.received_at",
                        r),
                q.period("select count(*)" + sold + member + "not " + prior, "p.paid_at", r),
                q.period("select count(*)" + sold + member + prior, "p.paid_at", r),
                q.period(
                        "select coalesce(sum(p.amount_fen),0)" + sold + member + "not " + prior,
                        "p.paid_at",
                        r),
                q.period(
                        "select coalesce(sum(p.amount_fen),0)" + sold + member + prior,
                        "p.paid_at",
                        r),
                q.sum("select coalesce(sum(i.quantity-i.restored_quantity),0)" + shipment),
                q.period(
                        "select count(*) from store_order o where " + scope.channel("o"),
                        "o.completed_at",
                        r),
                q.period(
                        "select count(*) from store_order o where o.channel='NO_CHARGE'",
                        "o.paid_at",
                        r),
                q.sum(
                        "select count(distinct mi.user_id) from membership_interval mi join"
                                + " membership_grant g on g.id=mi.grant_id join order_item si on"
                                + " si.id=g.order_item_id join store_order so on so.id=si.order_id"
                                + " where "
                                + scope.grant()
                                + " and mi.starts_at<=? and mi.expires_at>?",
                        ts(q.generatedAt),
                        ts(q.generatedAt)),
                q.period(
                        "select count(*)" + download + " and d.action='DOWNLOAD'",
                        "d.created_at",
                        r),
                q.period(
                        "select count(distinct d.user_id)" + download + " and d.action='DOWNLOAD'",
                        "d.created_at",
                        r),
                q.period(
                        "select count(*)" + download + " and d.action='PREVIEW'",
                        "d.created_at",
                        r),
                q.period(
                        "select coalesce(sum(l.pending_delta),0)"
                                + flow
                                + " and l.event_type='COMMISSION_ACCRUE'",
                        "l.created_at",
                        r),
                q.period(
                        "select coalesce(sum(-l.pending_delta-l.available_delta+l.debt_delta),0)"
                                + flow
                                + " and l.event_type='COMMISSION_REVERSE'",
                        "l.created_at",
                        r),
                q.sum("select coalesce(sum(l.available_delta),0)" + flow),
                q.sum("select coalesce(sum(l.pending_delta),0)" + flow),
                q.sum("select coalesce(sum(l.withdrawing_delta),0)" + flow),
                q.sum("select coalesce(sum(l.paid_out_delta),0)" + flow),
                q.period("select coalesce(sum(l.paid_out_delta),0)" + flow, "l.created_at", r),
                q.sum("select coalesce(sum(l.debt_delta),0)" + flow),
                q.sum(
                        "select count(*) from store_order o where o.financial_hold=true and "
                                + scope.channel("o")),
                q.period(
                        "select count(*) from download_audit d left join download_provenance dp on"
                                + " dp.audit_id=d.id where (dp.audit_id is null or"
                                + " dp.source_kind='UNPROVEN')",
                        "d.created_at",
                        r),
                q.period(
                        "select count(*) from store_order o where " + scope.channel("o"),
                        "o.created_at",
                        r),
                q.sum("select count(distinct o.id)" + shipment),
                scope.environment(),
                scope.includesDevChannel());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsDtos.Trend trend(LocalDate start, LocalDate end) {
        return trend(start, end, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsDtos.Trend trend(LocalDate start, LocalDate end, boolean includeDev) {
        var q = new Queries(range(start, end), true);
        var scope = scope(includeDev);
        List<AnalyticsDtos.Overview> rows = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1))
            rows.add(overview(day, day, scope, q));
        return new AnalyticsDtos.Trend(
                rows,
                TIMEZONE,
                new AnalyticsDtos.Period(start, end, TIMEZONE),
                q.generatedAt,
                scope.environment(),
                scope.includesDevChannel());
    }
}
