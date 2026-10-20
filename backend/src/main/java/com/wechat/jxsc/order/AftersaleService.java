package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.storage.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.fulfillment.*;
import com.wechat.jxsc.payment.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class AftersaleService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.ClosedAccountPrivacy privacy;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    @PersistenceContext private EntityManager em;
    private final OrderService orders;
    private final CommerceSupport s;
    private final SensitiveCipher cipher;
    private final AssetService assets;
    private final TaskQueue tasks;
    private final Clock clock;
    private final List<RefundPreviewContributor> previews;
    private final com.wechat.jxsc.catalog.InventoryService inventory;
    private final com.wechat.jxsc.fulfillment.CarrierCatalog carriers;

    public AftersaleService(
            OrderService orders,
            CommerceSupport s,
            SensitiveCipher cipher,
            AssetService assets,
            TaskQueue tasks,
            Clock clock,
            List<RefundPreviewContributor> previews,
            com.wechat.jxsc.catalog.InventoryService inventory,
            com.wechat.jxsc.fulfillment.CarrierCatalog carriers) {
        this.orders = orders;
        this.s = s;
        this.cipher = cipher;
        this.assets = assets;
        this.tasks = tasks;
        this.clock = clock;
        this.previews = previews;
        this.inventory = inventory;
        this.carriers = carriers;
    }

    @Transactional
    public JsonNode apply(long id, OrderDtos.ApplyRefund r, String key, boolean admin) {
        if (!admin) lifecycle.customer();
        return s.execute(
                admin ? "ADMIN_APPLY_REFUND" : "APPLY_REFUND",
                key,
                Map.of("orderId", id, "request", r),
                () -> {
                    var o = orders.lock(id);
                    orders.requireFinancialClear(o);
                    if (!admin) CurrentActor.require().requireOwner(o.userId);
                    if (!Set.of("PAID", "COMPLETED").contains(o.orderStatus)
                            || o.activeAftersaleId != null)
                        throw CommerceSupport.conflict(
                                "INVALID_STATE", "Order cannot accept another active aftersale");
                    if (!admin && !orders.canApply(o))
                        throw CommerceSupport.conflict(
                                "AFTERSALE_WINDOW_CLOSED",
                                "Contact support for a reasoned exception");
                    amounts(o, r.requestedGoodsFen(), r.requestedFreightFen());
                    var item = orders.item(id);
                    if (r.type().equals("RETURN_REFUND")
                            && (!item.type.equals("BOOK")
                                    || !Set.of("SHIPPED", "RECEIVED")
                                            .contains(o.fulfillmentStatus)))
                        throw CommerceSupport.invalid("Return refund requires shipped books");
                    var a = new AftersaleCase();
                    a.caseNo = "A-" + UUID.randomUUID();
                    a.orderId = id;
                    a.userId = o.userId;
                    a.type = r.type();
                    a.status = "REQUESTED";
                    a.reason = r.reason();
                    a.requestedGoodsFen = r.requestedGoodsFen();
                    a.requestedFreightFen = r.requestedFreightFen();
                    em.persist(a);
                    em.flush();
                    for (String aid : new LinkedHashSet<>(r.evidenceAssetIds())) {
                        if (admin)
                            assets.requireCustomerEvidence(CommerceSupport.id(aid), o.userId);
                        else
                            assets.requirePurpose(
                                    CommerceSupport.id(aid), "AFTERSALE_EVIDENCE", true);
                        var e = new AftersaleEvidence();
                        e.aftersaleId = a.getId();
                        e.assetId = CommerceSupport.id(aid);
                        em.persist(e);
                    }
                    o.activeAftersaleId = a.getId();
                    o.refundStatus = "REQUESTED";
                    tasks.enqueue(
                            "AFTERSALE_OPENED",
                            a.getId().toString(),
                            s.encode(Map.of("orderId", Long.toString(id))),
                            clock.instant());
                    s.audit(
                            "AFTERSALE_APPLY",
                            "AFTERSALE",
                            a.getId(),
                            admin ? r.reason() : "Customer applied for aftersale",
                            Map.of(
                                    "type",
                                    a.type,
                                    "goodsFen",
                                    a.requestedGoodsFen,
                                    "freightFen",
                                    a.requestedFreightFen));
                    return view(a);
                });
    }

    private void amounts(StoreOrder o, long goods, long freight) {
        if (goods < 0
                || freight < 0
                || goods + freight <= 0
                || goods > o.goodsAmountFen - o.refundedGoodsFen
                || freight > o.freightAmountFen - o.refundedFreightFen)
            throw CommerceSupport.invalid("Refund exceeds remaining goods or freight amount");
    }

    private AftersaleCase lock(long id) {
        var initial = em.find(AftersaleCase.class, id);
        if (initial == null) throw CommerceSupport.missing();
        orders.lock(initial.orderId);
        return s.lock(AftersaleCase.class, id);
    }

    private void decision(AftersaleCase a, StoreOrder o, OrderDtos.Decision r) {
        orders.requireFinancialClear(o);
        if (!a.status.equals("REQUESTED"))
            throw CommerceSupport.conflict("INVALID_STATE", "Aftersale is not awaiting a decision");
        amounts(o, r.goodsFen(), r.freightFen());
        if (r.goodsFen() > a.requestedGoodsFen || r.freightFen() > a.requestedFreightFen)
            throw CommerceSupport.invalid("Approval exceeds requested amount");
        var i = orders.item(o.getId());
        if (r.returnedQuantity() > i.quantity - i.restoredQuantity)
            throw CommerceSupport.invalid("Return quantity exceeds remaining quantity");
        if (!i.type.equals("BOOK") && r.returnedQuantity() != 0)
            throw CommerceSupport.invalid("Digital refunds have no returned quantity");
        if (a.type.equals("RETURN_REFUND")
                && (r.returnedQuantity() < 1
                        || r.returnAddress() == null
                        || r.returnAddress().isBlank()))
            throw CommerceSupport.invalid("Return approval requires quantity and return address");
        if (!a.type.equals("RETURN_REFUND")
                && !o.fulfillmentStatus.equals("PENDING_SHIPMENT")
                && r.returnedQuantity() != 0)
            throw CommerceSupport.invalid("Shipped refund-only cases cannot restock books");
    }

    @Transactional
    public OrderDtos.DecisionPreview preview(long id, OrderDtos.Decision r) {
        var a = lock(id);
        var o = orders.lock(a.orderId);
        decision(a, o, r);
        boolean full =
                o.refundedGoodsFen + r.goodsFen() == o.goodsAmountFen
                        && o.refundedFreightFen + r.freightFen() == o.freightAmountFen;
        var effects = s.mapper().createObjectNode();
        for (var p : previews)
            effects.set(p.name(), p.preview(o.getId(), r.goodsFen(), r.freightFen(), full));
        return new OrderDtos.DecisionPreview(
                r.goodsFen(),
                r.freightFen(),
                r.returnedQuantity(),
                full,
                orders.item(o.getId()).type.equals("BOOK")
                        ? "NOT_REQUIRED"
                        : effects.has("entitlement")
                                ? (full ? "REVOKE_ON_SUCCESS" : "RETAIN_ON_SUCCESS")
                                : "PENDING_TASK_5",
                effects.has("commission") ? "RECALCULATE_ON_SUCCESS" : "PENDING_TASK_6",
                effects);
    }

    @Transactional
    public JsonNode approve(long id, OrderDtos.Decision r, String key) {
        return s.execute(
                "APPROVE_REFUND",
                key,
                Map.of("aftersaleId", id, "request", r),
                () -> {
                    var a = lock(id);
                    var o = orders.lock(a.orderId);
                    decision(a, o, r);
                    a.approvedGoodsFen = r.goodsFen();
                    a.approvedFreightFen = r.freightFen();
                    a.returnedQuantity = r.returnedQuantity();
                    a.returnAddressCipher = cipher.encrypt(r.returnAddress());
                    if (a.type.equals("RETURN_REFUND")) {
                        a.status = "RETURN_REQUIRED";
                        o.refundStatus = a.status;
                    } else {
                        a.status = "PROCESSING";
                        o.refundStatus = a.status;
                        createRefund(
                                a,
                                o,
                                o.fulfillmentStatus.equals("PENDING_SHIPMENT")
                                        ? r.returnedQuantity()
                                        : 0);
                    }
                    s.audit(
                            "AFTERSALE_APPROVE",
                            "AFTERSALE",
                            id,
                            r.reason(),
                            Map.of(
                                    "goodsFen",
                                    r.goodsFen(),
                                    "freightFen",
                                    r.freightFen(),
                                    "returnedQuantity",
                                    r.returnedQuantity()));
                    return view(a);
                });
    }

    @Transactional
    public JsonNode reject(long id, OrderDtos.Reason r, String key) {
        return s.execute(
                "REJECT_REFUND",
                key,
                Map.of("aftersaleId", id, "request", r),
                () -> {
                    var a = lock(id);
                    if (!a.status.equals("REQUESTED"))
                        throw CommerceSupport.conflict(
                                "INVALID_STATE", "Only requested cases may be rejected");
                    var o = orders.lock(a.orderId);
                    a.status = "REJECTED";
                    if (Objects.equals(o.activeAftersaleId, a.getId())) {
                        o.activeAftersaleId = null;
                        o.refundStatus =
                                o.refundedGoodsFen + o.refundedFreightFen > 0
                                        ? "PARTIAL"
                                        : "REJECTED";
                    }
                    tasks.enqueue(
                            "AFTERSALE_CLOSED",
                            a.getId().toString(),
                            s.encode(Map.of("orderId", o.getId().toString())),
                            clock.instant());
                    s.audit(
                            "AFTERSALE_REJECT",
                            "AFTERSALE",
                            id,
                            r.reason(),
                            Map.of("status", a.status));
                    return view(a);
                });
    }

    @Transactional
    public JsonNode returnShipment(long id, FulfillmentDtos.ReturnShipment r, String key) {
        lifecycle.customer();
        return s.execute(
                "RETURN_SHIPMENT",
                key,
                Map.of("aftersaleId", id, "request", r),
                () -> {
                    var a = lock(id);
                    CurrentActor.require().requireOwner(a.userId);
                    if (!a.status.equals("RETURN_REQUIRED"))
                        throw CommerceSupport.conflict("INVALID_STATE", "Return is not approved");
                    carriers.require(r.carrierCode());
                    a.returnCarrierCode = r.carrierCode();
                    a.returnTrackingNo = r.trackingNo();
                    a.status = "RETURNING";
                    orders.lock(a.orderId).refundStatus = a.status;
                    return view(a);
                });
    }

    @Transactional
    public JsonNode receive(long id, FulfillmentDtos.ReceiveReturn r, String key) {
        return s.execute(
                "RECEIVE_RETURN",
                key,
                Map.of("aftersaleId", id, "request", r),
                () -> {
                    var a = lock(id);
                    if (!a.status.equals("RETURNING"))
                        throw CommerceSupport.conflict(
                                "INVALID_STATE", "Return shipment is required");
                    if (r.receivedQuantity() > a.returnedQuantity)
                        throw CommerceSupport.invalid("Received quantity exceeds approved return");
                    a.receivedQuantity = r.receivedQuantity();
                    a.receivedAt = clock.instant();
                    a.status = "PROCESSING";
                    var o = orders.lock(a.orderId);
                    o.refundStatus = "PROCESSING";
                    var item = orders.item(o.getId());
                    if (item.restoredQuantity + r.receivedQuantity() > item.quantity)
                        throw CommerceSupport.invalid("Received quantity exceeds remaining books");
                    if (r.receivedQuantity() > 0) {
                        inventory.change(
                                item.productId,
                                item.getId(),
                                "RETURN",
                                r.receivedQuantity(),
                                0,
                                "warehouse-return:" + a.getId(),
                                "Warehouse confirmed physical return",
                                true);
                        item.restoredQuantity += r.receivedQuantity();
                    }
                    // Physical stock is already restored, independent of the financial outcome.
                    createRefund(a, o, 0);
                    s.audit(
                            "RETURN_RECEIVED",
                            "AFTERSALE",
                            id,
                            r.reason(),
                            Map.of("receivedQuantity", r.receivedQuantity()));
                    return view(a);
                });
    }

    private void createRefund(AftersaleCase a, StoreOrder o, int quantity) {
        orders.requireFinancialClear(o);
        var r = new RefundTransaction();
        r.aftersaleId = a.getId();
        r.orderId = o.getId();
        r.paymentId = o.successfulPaymentId;
        r.outRefundNo = "R-" + UUID.randomUUID();
        r.channel = o.channel;
        r.goodsFen = a.approvedGoodsFen;
        r.freightFen = a.approvedFreightFen;
        r.state = "CREATED";
        r.reason = "Approved aftersale";
        r.restockQuantity = quantity;
        em.persist(r);
        em.flush();
        tasks.enqueue("REFUND_SYNC", r.getId().toString(), "{}", clock.instant());
    }

    @Transactional(readOnly = true)
    public OrderDtos.AftersaleView get(long id, boolean admin) {
        var a = em.find(AftersaleCase.class, id);
        if (a == null) throw CommerceSupport.missing();
        if (!admin) CurrentActor.require().requireOwner(a.userId);
        return view(a);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderDtos.AftersaleView> list(PageQuery p) {
        return new PageResult<>(
                em
                        .createQuery(
                                "select a from AftersaleCase a order by a.id desc",
                                AftersaleCase.class)
                        .setFirstResult(p.offset())
                        .setMaxResults(p.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::view)
                        .toList(),
                p.page(),
                p.pageSize(),
                em.createQuery("select count(a) from AftersaleCase a", Long.class)
                        .getSingleResult());
    }

    public OrderDtos.AftersaleView view(AftersaleCase a) {
        boolean closed = privacy.closed(a.userId);
        var ids =
                em
                        .createQuery(
                                "select e.assetId from AftersaleEvidence e where e.aftersaleId=:a",
                                Long.class)
                        .setParameter("a", a.getId())
                        .getResultList()
                        .stream()
                        .map(Object::toString)
                        .toList();
        return new OrderDtos.AftersaleView(
                a.getId().toString(),
                a.orderId.toString(),
                a.type,
                a.status,
                closed ? null : a.reason,
                a.requestedGoodsFen,
                a.requestedFreightFen,
                a.approvedGoodsFen,
                a.approvedFreightFen,
                a.returnedQuantity,
                a.receivedQuantity,
                closed ? null : cipher.decrypt(a.returnAddressCipher),
                a.returnCarrierCode,
                closed ? null : a.returnTrackingNo,
                closed ? List.of() : ids,
                a.status.equals("RETURN_REQUIRED") ? List.of("RETURN_SHIPMENT") : List.of());
    }

    @Transactional(readOnly = true)
    public long refundId(long id) {
        var a = em.find(AftersaleCase.class, id);
        if (a == null) throw CommerceSupport.missing();
        CurrentActor.require().requireOwner(a.userId);
        var rows =
                em.createQuery(
                                "select r from RefundTransaction r where r.aftersaleId=:a",
                                RefundTransaction.class)
                        .setParameter("a", id)
                        .getResultList();
        if (rows.isEmpty()) throw CommerceSupport.conflict("INVALID_STATE", "Refund not approved");
        return rows.getFirst().getId();
    }

    @Transactional(readOnly = true)
    public AssetService.ProtectedObject evidence(long id, long assetId, boolean admin) {
        var a = em.find(AftersaleCase.class, id);
        if (a == null) throw CommerceSupport.missing();
        if (privacy.closed(a.userId)) throw CommerceSupport.missing();
        if (!admin) CurrentActor.require().requireOwner(a.userId);
        if (em.createQuery(
                                "select count(e) from AftersaleEvidence e where e.aftersaleId=:a"
                                        + " and e.assetId=:f",
                                Long.class)
                        .setParameter("a", id)
                        .setParameter("f", assetId)
                        .getSingleResult()
                != 1) throw CommerceSupport.missing();
        return assets.protectedObject(assetId, "AFTERSALE_EVIDENCE", a.userId);
    }
}
