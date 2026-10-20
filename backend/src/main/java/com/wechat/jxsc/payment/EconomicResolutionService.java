package com.wechat.jxsc.payment;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.catalog.InventoryService;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.TaskQueue;
import com.wechat.jxsc.order.*;

import jakarta.persistence.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/** Books authenticated original-number outcomes. Operators cannot supply outcomes or amounts. */
@Service
public class EconomicResolutionService {
    @PersistenceContext private EntityManager em;
    private final JdbcTemplate db;
    private final OrderService orders;
    private final CommerceSupport s;
    private final SensitiveCipher cipher;
    private final InventoryService inventory;
    private final TaskQueue tasks;
    private final Clock clock;

    public EconomicResolutionService(
            JdbcTemplate db,
            OrderService orders,
            CommerceSupport s,
            SensitiveCipher cipher,
            InventoryService inventory,
            TaskQueue tasks,
            Clock clock) {
        this.db = db;
        this.orders = orders;
        this.s = s;
        this.cipher = cipher;
        this.inventory = inventory;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public EconomicDtos.Summary resolve(long id, String reason, String key) {
        return s.mapper()
                .convertValue(
                        s.execute(
                                "ORDER_ECONOMIC_RESOLUTION",
                                key,
                                Map.of("orderId", id, "reason", reason),
                                () -> resolveLocked(id, reason)),
                        EconomicDtos.Summary.class);
    }

    private Object resolveLocked(long id, String reason) {
        requireFinance();
        var order = orders.lock(id);
        if (!order.financialHold) return summary(id);
        if (db.queryForObject(
                                "select count(*) from financial_reconciliation where order_id=? and"
                                        + " resource_type='REFUND'",
                                Long.class,
                                id)
                        == 0
                || db.queryForObject(
                                "select count(*) from financial_reconciliation where order_id=? and"
                                        + " resource_type<>'REFUND' and status<>'RESOLVED'",
                                Long.class,
                                id)
                        > 0) throw pending();
        var refunds =
                em.createQuery(
                                "select r from RefundTransaction r where r.orderId=:id order by"
                                        + " r.id",
                                RefundTransaction.class)
                        .setParameter("id", id)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getResultList();
        Map<Long, Map<String, Object>> evidence = new LinkedHashMap<>();
        // Verify the complete dispatched set before appending any monetary effect.
        for (var refund : refunds) {
            if (refund.state.equals("SUCCEEDED") || resolved(refund.getId())) continue;
            var issues =
                    db.queryForList(
                            "select * from financial_reconciliation where resource_type='REFUND'"
                                    + " and transaction_id=? for update",
                            refund.getId());
            if (issues.isEmpty()) {
                if (refund.dispatchStartedAt != null
                        && !Set.of("FAILED", "CLOSED").contains(refund.state)) throw pending();
                continue;
            }
            var issue = issues.getFirst();
            String state = (String) issue.get("last_query_state");
            if (!"NEEDS_REVIEW".equals(issue.get("status"))
                    || !Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(state)
                    || Boolean.TRUE.equals(issue.get("success_observed"))
                            && !state.equals("SUCCEEDED")) throw pending();
            var rows =
                    db.queryForList(
                            "select * from reconciliation_query where reconciliation_id=? and"
                                    + " source='QUERY' order by id desc",
                            issue.get("id"));
            var payment = em.find(PaymentTransaction.class, refund.paymentId);
            Map<String, Object> matched = null;
            for (var row : rows) {
                try {
                    var observed =
                            s.mapper()
                                    .readValue(
                                            cipher.decrypt((String) row.get("payload_encrypted")),
                                            PaymentProvider.Result.class);
                    if (observed.state().name().equals(state)
                            && observed.amountFen() == refund.goodsFen + refund.freightFen
                            && Objects.equals(observed.appId(), payment.appId)
                            && Objects.equals(observed.merchantId(), payment.merchantId)
                            && "CNY".equals(observed.currency())
                            && (!state.equals("SUCCEEDED")
                                    || Objects.equals(
                                            observed.channelId(), issue.get("channel_id")))) {
                        matched =
                                Map.of(
                                        "queryId",
                                        row.get("id"),
                                        "state",
                                        state,
                                        "channelId",
                                        Objects.toString(observed.channelId(), ""));
                        break;
                    }
                } catch (Exception e) {
                    throw pending();
                }
            }
            if (matched == null) throw pending();
            evidence.put(refund.getId(), matched);
        }
        var item = orders.item(id);
        for (var refund : refunds) {
            if (resolved(refund.getId())) continue;
            var proof = evidence.get(refund.getId());
            if (proof != null && proof.get("state").equals("SUCCEEDED")) {
                if (refund.exceptional)
                    throw CommerceSupport.conflict(
                            "RESOLUTION_UNSUPPORTED",
                            "Exceptional late-payment refunds require their original recovery"
                                    + " workflow");
                long goods =
                        Math.min(refund.goodsFen, order.goodsAmountFen - order.refundedGoodsFen);
                long freight =
                        Math.min(
                                refund.freightFen,
                                order.freightAmountFen - order.refundedFreightFen);
                long excess = refund.goodsFen + refund.freightFen - goods - freight;
                var aftersale = s.lock(AftersaleCase.class, refund.aftersaleId);
                int restock =
                        Math.min(refund.restockQuantity, item.quantity - item.restoredQuantity);
                if (restock > 0) {
                    boolean received =
                            aftersale.type.equals("RETURN_REFUND")
                                    && aftersale.receivedAt != null
                                    && aftersale.receivedQuantity >= restock;
                    if (!item.type.equals("BOOK")
                            || !order.fulfillmentStatus.equals("PENDING_SHIPMENT") && !received)
                        throw CommerceSupport.conflict(
                                "RESTOCK_EVIDENCE_REQUIRED",
                                "Physical restoration requires recorded received quantity");
                    inventory.change(
                            item.productId,
                            item.getId(),
                            "RETURN",
                            restock,
                            0,
                            "correction-refund:" + refund.getId(),
                            reason,
                            true);
                    item.restoredQuantity += restock;
                }
                db.update(
                        "insert into"
                            + " economic_resolution(resource_type,transaction_id,order_id,channel_id,observed_fen,goods_fen,freight_fen,excess_fen,evidence_json,actor_id,reason,request_id)"
                            + " values('REFUND',?,?,?,?,?,?,?,?,?,?,?)",
                        refund.getId(),
                        id,
                        proof.get("channelId"),
                        refund.goodsFen + refund.freightFen,
                        goods,
                        freight,
                        excess,
                        s.encode(proof),
                        CurrentActor.require().id(),
                        reason,
                        org.slf4j.MDC.get("requestId") == null
                                ? "internal"
                                : org.slf4j.MDC.get("requestId"));
                order.refundedGoodsFen += goods;
                order.refundedFreightFen += freight;
                item.goodsRefundedFen += goods;
                aftersale.status =
                        order.refundedGoodsFen == order.goodsAmountFen
                                        && order.refundedFreightFen == order.freightAmountFen
                                ? "FULL"
                                : "PARTIAL";
                tasks.enqueue(
                        "REFUND_SUCCEEDED",
                        "correction:" + refund.getId(),
                        s.encode(Map.of("orderId", "" + id, "refundId", refund.getId().toString())),
                        clock.instant());
                s.audit(
                        "REFUND_ECONOMIC_CORRECTION",
                        "REFUND",
                        refund.getId(),
                        reason,
                        Map.of(
                                "retainedState",
                                refund.state,
                                "observedFen",
                                refund.goodsFen + refund.freightFen,
                                "goodsFen",
                                goods,
                                "freightFen",
                                freight,
                                "excessLossFen",
                                excess,
                                "queryId",
                                proof.get("queryId")));
            }
            // Suppression is a local dispatch decision; it is not a channel cancellation/state.
            if (refund.dispatchStartedAt == null
                    && !Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(refund.state)) {
                refund.dispatchSuppressed = true;
                s.audit(
                        "REFUND_UNSENT_SUPPRESSED",
                        "REFUND",
                        refund.getId(),
                        reason,
                        Map.of("state", refund.state));
            }
        }
        db.update(
                "update financial_reconciliation set"
                        + " status='RESOLVED',version=version+1,updated_at=UTC_TIMESTAMP(6) where"
                        + " order_id=? and resource_type='REFUND'",
                id);
        order.refundStatus =
                order.refundedGoodsFen == order.goodsAmountFen
                                && order.refundedFreightFen == order.freightAmountFen
                        ? "FULL"
                        : order.refundedGoodsFen + order.refundedFreightFen > 0
                                ? "PARTIAL"
                                : order.refundStatus;
        if (order.activeAftersaleId != null) {
            var active = s.lock(AftersaleCase.class, order.activeAftersaleId);
            active.status = order.refundStatus.equals("FULL") ? "FULL" : "REJECTED";
            tasks.enqueue(
                    "AFTERSALE_CLOSED",
                    "correction:" + active.getId(),
                    s.encode(Map.of("orderId", "" + id)),
                    clock.instant());
            order.activeAftersaleId = null;
        }
        order.financialHold = false;
        em.flush();
        s.audit("ORDER_ECONOMIC_RESOLUTION", "ORDER", id, reason, summary(id));
        return summary(id);
    }

    private boolean resolved(long id) {
        return db.queryForObject(
                        "select count(*) from economic_resolution where resource_type='REFUND' and"
                                + " transaction_id=?",
                        Long.class,
                        id)
                > 0;
    }

    @Transactional(readOnly = true)
    public EconomicDtos.Summary summary(long id) {
        requireFinance();
        return db.queryForObject(
                "select"
                    + " coalesce(sum(observed_fen),0),coalesce(sum(goods_fen),0),coalesce(sum(freight_fen),0),coalesce(sum(excess_fen),0)"
                    + " from economic_resolution where order_id=?",
                (r, n) ->
                        new EconomicDtos.Summary(
                                "" + id, r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4)),
                id);
    }

    @Transactional(readOnly = true)
    public com.wechat.jxsc.common.PageResult<EconomicDtos.Receipt> history(
            long id, com.wechat.jxsc.common.PageQuery page) {
        requireFinance();
        var receipts =
                db.query(
                        "select * from economic_resolution where order_id=? order by id desc limit"
                                + " ? offset ?",
                        (r, n) ->
                                new EconomicDtos.Receipt(
                                        r.getString("id"),
                                        r.getString("transaction_id"),
                                        r.getLong("observed_fen"),
                                        r.getLong("goods_fen"),
                                        r.getLong("freight_fen"),
                                        r.getLong("excess_fen"),
                                        r.getString("actor_id"),
                                        r.getString("reason"),
                                        r.getString("request_id"),
                                        r.getTimestamp("created_at").toInstant()),
                        id,
                        page.pageSize(),
                        page.offset());
        return new com.wechat.jxsc.common.PageResult<>(
                receipts,
                page.page(),
                page.pageSize(),
                db.queryForObject(
                        "select count(*) from economic_resolution where order_id=?",
                        Long.class,
                        id));
    }

    private static RuntimeException pending() {
        return CommerceSupport.conflict(
                "RECONCILIATION_EVIDENCE_REQUIRED",
                "Every dispatched refund must have consistent authenticated original-number"
                        + " terminal evidence");
    }

    public static void requireFinance() {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        if (!actor.permissions().contains("FINANCE_OPERATE"))
            throw new com.wechat.jxsc.common.ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "Finance permission required");
    }
}
