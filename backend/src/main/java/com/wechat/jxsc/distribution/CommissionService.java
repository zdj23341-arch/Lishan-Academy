package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.*;
import java.util.*;

@Service
public class CommissionService {
    private final FinanceStore f;
    private final CommerceSupport s;
    private final OrderService orders;
    private final TaskQueue tasks;

    public CommissionService(
            FinanceStore f, CommerceSupport s, OrderService orders, TaskQueue tasks) {
        this.f = f;
        this.s = s;
        this.orders = orders;
        this.tasks = tasks;
    }

    public static long commission(long fen, long bps) {
        return BigInteger.valueOf(fen)
                .multiply(BigInteger.valueOf(bps))
                .divide(BigInteger.valueOf(10000))
                .longValueExact();
    }

    public FinanceDtos.Balances balances(Map<String, Object> row) {
        return new FinanceDtos.Balances(
                n(row, "pending_fen"),
                n(row, "available_fen"),
                n(row, "withdrawing_fen"),
                n(row, "paid_out_fen"),
                n(row, "debt_fen"));
    }

    public Map<String, Object> account(long distributor) {
        return f.one(
                "select * from commission_account where distributor_id=? for update", distributor);
    }

    public void change(
            long distributor,
            String key,
            String type,
            long pending,
            long available,
            long withdrawing,
            long paid,
            long debt,
            Long order,
            Long refund,
            Long withdrawal) {
        var before = balances(account(distributor));
        if (f.db.queryForObject(
                        "select count(*) from commission_account_ledger where business_key=?",
                        Long.class,
                        key)
                > 0) return;
        var after =
                new FinanceDtos.Balances(
                        Math.addExact(before.pendingFen(), pending),
                        Math.addExact(before.availableFen(), available),
                        Math.addExact(before.withdrawingFen(), withdrawing),
                        Math.addExact(before.paidOutFen(), paid),
                        Math.addExact(before.debtFen(), debt));
        if (after.pendingFen() < 0
                || after.availableFen() < 0
                || after.withdrawingFen() < 0
                || after.paidOutFen() < 0
                || after.debtFen() < 0)
            throw new IllegalStateException("Negative commission account");
        f.db.update(
                "update commission_account set"
                    + " pending_fen=?,available_fen=?,withdrawing_fen=?,paid_out_fen=?,debt_fen=?,version=version+1,updated_at=?"
                    + " where distributor_id=?",
                after.pendingFen(),
                after.availableFen(),
                after.withdrawingFen(),
                after.paidOutFen(),
                after.debtFen(),
                f.now(),
                distributor);
        f.db.update(
                "insert into"
                    + " commission_account_ledger(distributor_id,business_key,event_type,pending_delta,available_delta,withdrawing_delta,paid_out_delta,debt_delta,before_json,after_json,order_id,refund_id,withdrawal_id,created_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                distributor,
                key,
                type,
                pending,
                available,
                withdrawing,
                paid,
                debt,
                s.encode(before),
                s.encode(after),
                order,
                refund,
                withdrawal,
                f.now());
        s.systemAudit(
                type,
                "COMMISSION_ACCOUNT",
                distributor,
                key,
                Map.of("before", before, "after", after));
    }

    @Transactional
    public void reconcile(long orderId, Long refundId) {
        var o = orders.lock(orderId);
        orders.requireFinancialClear(o);
        var item = orders.item(orderId);
        var at = f.db.queryForList("select * from attribution where order_id=?", orderId);
        if (at.isEmpty() || o.paidAt == null) return;
        var a = at.getFirst();
        long did = n(a, "distributor_id");
        f.one("select id from distributor where id=? for update", did);
        account(did);
        var existing =
                f.db.queryForList(
                        "select * from commission_ledger where order_id=? for update", orderId);
        if (existing.isEmpty()) {
            long earned = commission(n(a, "base_fen"), n(a, "rate_bps"));
            f.db.update(
                    "insert into"
                        + " commission_ledger(order_id,order_item_id,distributor_id,base_fen,rate_bps,earned_fen,state,observation_days,created_at,updated_at)"
                        + " values(?,?,?,?,?,?,'PENDING',?,?,?)",
                    orderId,
                    item.getId(),
                    did,
                    n(a, "base_fen"),
                    n(a, "rate_bps"),
                    earned,
                    n(a, "observation_days"),
                    f.now(),
                    f.now());
            change(
                    did,
                    "accrue:" + orderId,
                    "COMMISSION_ACCRUE",
                    earned,
                    0,
                    0,
                    0,
                    0,
                    orderId,
                    null,
                    null);
        }
        var c = f.one("select * from commission_ledger where order_id=? for update", orderId);
        long
                total =
                        n(c, "earned_fen")
                                - commission(
                                        Math.max(0, n(c, "base_fen") - o.refundedGoodsFen),
                                        n(c, "rate_bps")),
                delta = total - n(c, "reversed_fen");
        if (delta > 0) {
            if (str(c, "state").equals("PENDING"))
                change(
                        did,
                        "reverse:" + orderId + ":" + total,
                        "COMMISSION_REVERSE",
                        -delta,
                        0,
                        0,
                        0,
                        0,
                        orderId,
                        refundId,
                        null);
            else {
                long available = balances(account(did)).availableFen(),
                        take = Math.min(delta, available);
                change(
                        did,
                        "reverse:" + orderId + ":" + total,
                        "COMMISSION_REVERSE",
                        0,
                        -take,
                        0,
                        0,
                        delta - take,
                        orderId,
                        refundId,
                        null);
            }
            f.db.update(
                    "update commission_ledger set reversed_fen=?,updated_at=? where order_id=?",
                    total,
                    f.now(),
                    orderId);
        }
        if (o.completedAt != null) {
            Instant eligible = o.completedAt.plusSeconds(n(c, "observation_days") * 86400L);
            f.db.update(
                    "update commission_ledger set eligible_at=?,updated_at=? where order_id=?",
                    ts(eligible),
                    f.now(),
                    orderId);
            tasks.enqueue("COMMISSION_MATURE", "" + orderId, "{}", eligible);
        }
    }

    @Transactional
    public void mature(long orderId) {
        var o = orders.lock(orderId);
        orders.requireFinancialClear(o);
        reconcile(orderId, null);
        var rows = f.db.queryForList("select * from commission_ledger where order_id=?", orderId);
        if (rows.isEmpty()) return;
        var c = rows.getFirst();
        long did = n(c, "distributor_id");
        var d = f.one("select * from distributor where id=? for update", did);
        account(did);
        c = f.one("select * from commission_ledger where order_id=? for update", orderId);
        if (str(c, "state").equals("AVAILABLE")) return;
        if (o.activeAftersaleId != null
                || bool(d, "risk_blocked")
                || o.completedAt == null
                || time(c, "eligible_at") == null
                || time(c, "eligible_at").isAfter(f.clock.instant()))
            throw new TaskHandler.RetryLater("COMMISSION_NOT_MATURE", Duration.ofHours(1));
        long remaining = n(c, "earned_fen") - n(c, "reversed_fen"),
                repay = Math.min(remaining, balances(account(did)).debtFen());
        change(
                did,
                "mature:" + orderId,
                "COMMISSION_MATURE",
                -remaining,
                remaining - repay,
                0,
                0,
                -repay,
                orderId,
                null,
                null);
        f.db.update(
                "update commission_ledger set state='AVAILABLE',updated_at=? where order_id=?",
                f.now(),
                orderId);
    }

    public PageResult<FinanceDtos.CommissionView> list(Long did, PageQuery q) {
        String where = did == null ? "" : " where distributor_id=" + did;
        var list =
                f.db.queryForList(
                        "select * from commission_ledger"
                                + where
                                + " order by id desc limit ? offset ?",
                        q.pageSize(),
                        q.offset());
        return new PageResult<>(
                list.stream()
                        .map(
                                c ->
                                        new FinanceDtos.CommissionView(
                                                "" + n(c, "id"),
                                                "" + n(c, "order_id"),
                                                "" + n(c, "distributor_id"),
                                                n(c, "base_fen"),
                                                (int) n(c, "rate_bps"),
                                                n(c, "earned_fen"),
                                                n(c, "reversed_fen"),
                                                n(c, "earned_fen") - n(c, "reversed_fen"),
                                                n(c, "reversed_fen") > 0
                                                        ? (n(c, "reversed_fen")
                                                                        >= n(c, "earned_fen")
                                                                ? "REVERSED"
                                                                : "PARTIALLY_REVERSED")
                                                        : str(c, "state"),
                                                time(c, "eligible_at")))
                        .toList(),
                q.page(),
                q.pageSize(),
                f.db.queryForObject("select count(*) from commission_ledger" + where, Long.class));
    }
}
