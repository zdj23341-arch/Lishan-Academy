package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.catalog.*;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.fulfillment.*;
import com.wechat.jxsc.payment.*;

import jakarta.persistence.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
public class OrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    @PersistenceContext private EntityManager em;
    private final CommerceSupport s;
    private final CheckoutCatalog catalog;
    private final InventoryService inventory;
    private final AddressService addresses;
    private final ShippingRuleService shipping;
    private final TaskQueue tasks;
    private final Clock clock;
    private final Environment env;
    private final List<CheckoutPolicy> policies;
    private final List<AttributionResolver> resolvers;

    public OrderService(
            CommerceSupport s,
            CheckoutCatalog catalog,
            InventoryService inventory,
            AddressService addresses,
            ShippingRuleService shipping,
            TaskQueue tasks,
            Clock clock,
            Environment env,
            List<CheckoutPolicy> policies,
            List<AttributionResolver> resolvers) {
        this.s = s;
        this.catalog = catalog;
        this.inventory = inventory;
        this.addresses = addresses;
        this.shipping = shipping;
        this.tasks = tasks;
        this.clock = clock;
        this.env = env;
        this.policies = policies;
        this.resolvers = resolvers;
    }

    @Transactional
    public OrderDtos.Quote preview(OrderDtos.Preview r) {
        lifecycle.customer();
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        s.lock(UserAccount.class, actor.id());
        var p = catalog.read(CommerceSupport.id(r.productId()));
        if (r.quantity() > p.quantityLimit())
            throw CommerceSupport.invalid("Quantity exceeds product limit");
        var q = new OrderQuote();
        q.userId = actor.id();
        q.productId = p.productId();
        q.productVersion = p.version();
        q.termsVersion = p.termsVersion();
        q.quantity = r.quantity();
        q.goodsAmountFen = Math.multiplyExact(p.unitPriceFen(), r.quantity());
        q.expiresAt = clock.instant().plusSeconds(300);
        q.snapshotJson = s.encode(p);
        int available = 0;
        if (p.type().equals("BOOK")) {
            if (r.addressId() == null)
                throw CommerceSupport.invalid("A book shipping address is required");
            var a = addresses.owned(CommerceSupport.id(r.addressId()), true);
            var rule = shipping.match(a.provinceCode);
            q.addressId = a.getId();
            q.addressVersion = a.getVersion();
            q.shippingRuleId = rule.getId();
            q.shippingRuleVersion = rule.getVersion();
            q.freightAmountFen = shipping.freight(rule, q.goodsAmountFen);
            available = inventory.available(p.productId());
            if (available < q.quantity)
                throw CommerceSupport.conflict(
                        "INSUFFICIENT_STOCK", "Insufficient available inventory");
        } else if (r.addressId() != null)
            throw CommerceSupport.invalid("Digital products do not use a shipping address");
        String quoteToken =
                new java.math.BigInteger(256, new java.security.SecureRandom())
                        .setBit(255)
                        .toString();
        q.tokenHash = TokenCodec.hash(quoteToken);
        em.persist(q);
        em.flush();
        return new OrderDtos.Quote(
                quoteToken,
                q.expiresAt,
                new OrderDtos.Item(
                        null,
                        Long.toString(p.productId()),
                        p.type(),
                        p.title(),
                        q.quantity,
                        0,
                        p.type().equals("BOOK") ? q.quantity : 0,
                        0,
                        p.unitPriceFen(),
                        s.node(p.fulfillment())),
                available,
                q.goodsAmountFen,
                q.freightAmountFen,
                q.goodsAmountFen + q.freightAmountFen,
                "CNY",
                List.of());
    }

    @Transactional
    public JsonNode create(OrderDtos.Create r, String key) {
        lifecycle.customer();
        return s.execute(
                "CREATE_ORDER",
                key,
                r,
                () -> {
                    var actor = CurrentActor.require();
                    actor.requireType(CurrentActor.Type.CUSTOMER);
                    s.lock(UserAccount.class, actor.id());
                    var quotes =
                            em.createQuery(
                                            "select q from OrderQuote q where q.tokenHash=:h",
                                            OrderQuote.class)
                                    .setParameter("h", TokenCodec.hash(r.quoteId()))
                                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                                    .getResultList();
                    if (quotes.isEmpty()) throw CommerceSupport.missing();
                    var q = quotes.getFirst();
                    em.refresh(q, LockModeType.PESSIMISTIC_WRITE);
                    actor.requireOwner(q.userId);
                    if (q.orderId != null) return view(lock(q.orderId));
                    if (!q.expiresAt.isAfter(clock.instant())) throw CommerceSupport.stale();
                    var p = catalog.read(q.productId);
                    if (p.version() != q.productVersion
                            || !Objects.equals(p.termsVersion(), q.termsVersion)
                            || q.quantity > p.quantityLimit()
                            || q.goodsAmountFen != p.unitPriceFen() * q.quantity)
                        throw CommerceSupport.stale();
                    UserAddress a = null;
                    if (q.addressId != null) {
                        try {
                            a = addresses.owned(q.addressId, true);
                            var rule = shipping.match(a.provinceCode);
                            if (a.getVersion() != q.addressVersion
                                    || !rule.getId().equals(q.shippingRuleId)
                                    || rule.getVersion() != q.shippingRuleVersion
                                    || shipping.freight(rule, q.goodsAmountFen)
                                            != q.freightAmountFen) throw CommerceSupport.stale();
                        } catch (ApiException e) {
                            throw CommerceSupport.stale();
                        }
                    }
                    for (var policy : policies) policy.validate(actor.id(), p);
                    if (r.attributionToken() != null
                            && !r.attributionToken().isBlank()
                            && resolvers.isEmpty())
                        throw CommerceSupport.invalid("Attribution token is not recognized");
                    var o = new StoreOrder();
                    o.userId = actor.id();
                    o.quoteId = q.getId();
                    o.orderNo =
                            ("DEV".equals(env.getProperty("jxsc.providers.payment"))
                                            ? "DEV-"
                                            : "JXSC-")
                                    + UUID.randomUUID();
                    o.channel =
                            "DEV".equals(env.getProperty("jxsc.providers.payment", "REAL"))
                                    ? "DEV"
                                    : "WECHAT_PAY";
                    o.orderStatus = "PENDING_PAYMENT";
                    o.refundStatus = "NONE";
                    o.fulfillmentStatus =
                            p.type().equals("BOOK") ? "PENDING_SHIPMENT" : "NOT_REQUIRED";
                    o.goodsAmountFen = q.goodsAmountFen;
                    o.freightAmountFen = q.freightAmountFen;
                    o.payableAmountFen = o.goodsAmountFen + o.freightAmountFen;
                    o.expiresAt = clock.instant().plusSeconds(1800);
                    o.attributionSnapshotJson = "{}";
                    em.persist(o);
                    q.orderId = o.getId();
                    if (!resolvers.isEmpty()) {
                        if (resolvers.size() != 1)
                            throw new IllegalStateException("One attribution resolver required");
                        o.attributionSnapshotJson =
                                s.encode(
                                        resolvers
                                                .getFirst()
                                                .resolveAndLock(
                                                        actor.id(),
                                                        p.productId(),
                                                        r.attributionToken(),
                                                        o.getId(),
                                                        o.goodsAmountFen));
                    }
                    var i = new OrderItem();
                    i.orderId = o.getId();
                    i.productId = p.productId();
                    i.quantity = q.quantity;
                    i.unitPriceFen = p.unitPriceFen();
                    i.goodsPaidFen = q.goodsAmountFen;
                    i.type = p.type();
                    i.titleSnapshot = p.title();
                    i.fulfillmentSnapshotJson = s.encode(p.fulfillment());
                    i.stockState = p.type().equals("BOOK") ? "RESERVED" : "NONE";
                    em.persist(i);
                    em.flush();
                    if (a != null) {
                        addresses.snapshot(o.getId(), a);
                        inventory.change(
                                i.productId,
                                i.getId(),
                                "RESERVE",
                                0,
                                i.quantity,
                                "reserve:" + i.getId(),
                                "Order created",
                                true);
                    }
                    tasks.enqueue("ORDER_EXPIRE", o.getId().toString(), "{}", o.expiresAt);
                    s.audit(
                            "ORDER_CREATE",
                            "ORDER",
                            o.getId(),
                            "Customer created order",
                            Map.of("amountFen", o.payableAmountFen));
                    if (o.payableAmountFen == 0) {
                        o.channel = "NO_CHARGE";
                        markPaid(o, null);
                        s.systemAudit(
                                "NO_CHARGE_COMPLETE",
                                "ORDER",
                                o.getId(),
                                "Server verified zero payable amount",
                                Map.of("amountFen", 0));
                    }
                    em.flush();
                    return view(o);
                });
    }

    @Transactional(readOnly = true)
    public OrderDtos.View get(long id, boolean admin) {
        var o = em.find(StoreOrder.class, id);
        if (o == null) throw CommerceSupport.missing();
        if (!admin) CurrentActor.require().requireOwner(o.userId);
        return view(o);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderDtos.View> list(String tab, PageQuery page, boolean admin) {
        String filter =
                switch (tab) {
                    case "ALL" -> "";
                    case "UNPAID" -> " and o.orderStatus='PENDING_PAYMENT'";
                    case "UNSHIPPED" ->
                            " and o.orderStatus='PAID' and o.fulfillmentStatus='PENDING_SHIPMENT'"
                                    + " and o.refundStatus<>'FULL'";
                    case "SHIPPING" ->
                            " and o.fulfillmentStatus='SHIPPED' and o.refundStatus<>'FULL'";
                    case "COMPLETED" -> " and o.orderStatus='COMPLETED'";
                    case "AFTERSALE" -> " and o.refundStatus<>'NONE'";
                    default -> throw CommerceSupport.invalid("Unknown order tab");
                };
        String where = " where 1=1" + (admin ? "" : " and o.userId=:u") + filter;
        var query =
                em.createQuery(
                        "select o from StoreOrder o" + where + " order by o.id desc",
                        StoreOrder.class);
        var count = em.createQuery("select count(o) from StoreOrder o" + where, Long.class);
        if (!admin) {
            query.setParameter("u", CurrentActor.require().id());
            count.setParameter("u", CurrentActor.require().id());
        }
        return new PageResult<>(
                query
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList()
                        .stream()
                        .map(this::view)
                        .toList(),
                page.page(),
                page.pageSize(),
                count.getSingleResult());
    }

    public StoreOrder lock(long id) {
        return s.lock(StoreOrder.class, id);
    }

    public OrderItem item(long id) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isCurrentTransactionReadOnly()) {
            // A callback can establish an RR snapshot before waiting for cancellation. Read the
            // item currently protected by the order lock, not its pre-cancellation stock state.
            var ids =
                    em.createNativeQuery("select id from order_item where order_id=:o for update")
                            .setParameter("o", id)
                            .getResultList();
            if (ids.isEmpty()) throw CommerceSupport.missing();
            return s.lock(OrderItem.class, ((Number) ids.getFirst()).longValue());
        }
        return em.createQuery("select i from OrderItem i where i.orderId=:o", OrderItem.class)
                .setParameter("o", id)
                .getSingleResult();
    }

    public OrderDtos.View view(StoreOrder o) {
        var i = item(o.getId());
        List<String> actions = new ArrayList<>();
        if (o.orderStatus.equals("PENDING_PAYMENT")) {
            if (!o.cancelRequested && o.expiresAt.isAfter(clock.instant())) actions.add("PAY");
            actions.add("CANCEL");
        }
        if (o.fulfillmentStatus.equals("SHIPPED")
                && !o.financialHold
                && o.activeAftersaleId == null
                && !o.refundStatus.equals("FULL")) actions.add("CONFIRM_RECEIPT");
        if (canApply(o)) actions.add("APPLY_AFTERSALE");
        if (i.type.equals("BOOK") && shippedQuantity(o.getId()) > 0) actions.add("VIEW_LOGISTICS");
        if (!i.type.equals("BOOK")
                && (o.payableAmountFen == 0 || o.refundedGoodsFen < o.goodsAmountFen)
                && ((Number)
                                        em.createNativeQuery(
                                                        "select count(*) from entitlement e where"
                                                            + " e.order_item_id=:item and"
                                                            + " ((e.entitlement_type='COURSE_VIEW'"
                                                            + " and e.starts_at<=:now and"
                                                            + " (e.expires_at is null or"
                                                            + " e.expires_at>:now)) or"
                                                            + " (e.entitlement_type='MEMBER_PAPER'"
                                                            + " and exists(select 1 from"
                                                            + " membership_grant g join"
                                                            + " membership_interval mi on"
                                                            + " mi.grant_id=g.id where"
                                                            + " g.entitlement_id=e.id and"
                                                            + " mi.starts_at<=:now and"
                                                            + " mi.expires_at>:now))) and not"
                                                            + " exists(select 1 from"
                                                            + " entitlement_event v where"
                                                            + " v.source_key=e.source_key and"
                                                            + " v.event_type='REVOKE')")
                                                .setParameter("item", i.getId())
                                                .setParameter(
                                                        "now",
                                                        java.sql.Timestamp.from(clock.instant()))
                                                .getSingleResult())
                                .longValue()
                        > 0) actions.add("VIEW_CONTENT");
        return new OrderDtos.View(
                o.getId().toString(),
                o.orderNo,
                o.getVersion(),
                o.orderStatus,
                o.refundStatus,
                o.fulfillmentStatus,
                o.channel,
                new OrderDtos.Item(
                        i.getId().toString(),
                        i.productId.toString(),
                        i.type,
                        i.titleSnapshot,
                        i.quantity,
                        i.restoredQuantity,
                        i.type.equals("BOOK") && o.fulfillmentStatus.equals("PENDING_SHIPMENT")
                                ? i.quantity - i.restoredQuantity
                                : 0,
                        shippedQuantity(o.getId()),
                        i.unitPriceFen,
                        s.decode(i.fulfillmentSnapshotJson)),
                o.goodsAmountFen,
                o.freightAmountFen,
                o.payableAmountFen,
                confirmedPaid(o.getId()),
                o.refundedGoodsFen,
                o.refundedFreightFen,
                "CNY",
                o.expiresAt,
                o.paidAt,
                o.completedAt,
                addresses.snapshotView(o.getId()),
                o.financialHold,
                reconciliationView(o),
                actions);
    }

    private long confirmedPaid(long order) {
        // One contribution per original payment; observed conflicts are not added twice.
        return ((Number)
                        em.createNativeQuery(
                                        "select coalesce(sum(p.amount_fen),0) from"
                                            + " payment_transaction p where p.order_id=:id and"
                                            + " (p.state='SUCCEEDED' or exists(select 1 from"
                                            + " financial_reconciliation r where"
                                            + " r.resource_type='PAYMENT' and r.transaction_id=p.id"
                                            + " and r.success_observed=true))")
                                .setParameter("id", order)
                                .getSingleResult())
                .longValue();
    }

    public boolean canApply(StoreOrder o) {
        return Set.of("PAID", "COMPLETED").contains(o.orderStatus)
                && !o.financialHold
                && o.activeAftersaleId == null
                && o.refundedGoodsFen + o.refundedFreightFen < o.payableAmountFen
                && (o.completedAt == null
                        || o.completedAt.plus(Duration.ofDays(7)).isAfter(clock.instant()));
    }

    public int shippedQuantity(long orderId) {
        var rows =
                em.createQuery(
                                "select p.shippedQuantity from Shipment p where p.orderId=:o",
                                Integer.class)
                        .setParameter("o", orderId)
                        .getResultList();
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    /** Must be checked under the order lock before fulfillment or downstream economic effects. */
    public void requireFinancialClear(StoreOrder order) {
        if (order.financialHold)
            throw CommerceSupport.conflict(
                    "FINANCIAL_RECONCILIATION_REQUIRED",
                    "Order has unresolved channel money; financial operations are held");
    }

    private ReconciliationDtos.View reconciliationView(StoreOrder o) {
        var issues =
                em.createQuery(
                                "select f from FinancialReconciliation f where f.orderId=:o order"
                                        + " by f.id",
                                FinancialReconciliation.class)
                        .setParameter("o", o.getId())
                        .getResultList();
        var refunds =
                em.createQuery(
                                "select r from RefundTransaction r where r.orderId=:o",
                                RefundTransaction.class)
                        .setParameter("o", o.getId())
                        .getResultList();
        long confirmed = 0, possible = 0, exceptionalConfirmed = 0, exceptionalPossible = 0;
        for (var refund : refunds) {
            var issue =
                    issues.stream()
                            .filter(
                                    f ->
                                            f.resourceType.equals("REFUND")
                                                    && f.transactionId.equals(refund.getId()))
                            .findFirst()
                            .orElse(null);
            boolean paid =
                    refund.state.equals("SUCCEEDED") || issue != null && issue.successObserved;
            boolean inFlight =
                    !paid
                            && refund.dispatchStartedAt != null
                            && !Set.of("FAILED", "CLOSED").contains(refund.state)
                            && (issue == null
                                    || !Set.of("FAILED", "CLOSED").contains(issue.lastQueryState));
            long amount = refund.goodsFen + refund.freightFen;
            if (refund.exceptional) {
                if (paid) exceptionalConfirmed += amount;
                else if (inFlight) exceptionalPossible += amount;
            } else {
                if (paid) confirmed += amount;
                else if (inFlight) possible += amount;
            }
        }
        return new ReconciliationDtos.View(
                confirmed,
                possible,
                exceptionalConfirmed,
                exceptionalPossible,
                confirmed > o.payableAmountFen,
                issues.stream()
                        .map(
                                f ->
                                        new ReconciliationDtos.Issue(
                                                f.resourceType,
                                                f.transactionId.toString(),
                                                f.reason,
                                                f.status,
                                                f.lastQueryState,
                                                f.successObserved,
                                                f.amountFen))
                        .toList());
    }

    /**
     * Must be called while holding the order row lock, after verified server/provider confirmation.
     */
    public void markPaid(StoreOrder o, Long paymentId) {
        requireFinancialClear(o);
        if (o.orderStatus.equals("PAID") || o.orderStatus.equals("COMPLETED")) return;
        var i = item(o.getId());
        if (i.type.equals("BOOK")) {
            if (i.stockState.equals("RELEASED")) {
                if (!inventory.change(
                        i.productId,
                        i.getId(),
                        "RECOVER",
                        0,
                        i.quantity,
                        "recover:" + i.getId(),
                        "Verified late payment",
                        false))
                    throw CommerceSupport.conflict(
                            "INSUFFICIENT_STOCK", "Cannot fulfill late payment");
                i.stockState = "RESERVED";
            }
            inventory.change(
                    i.productId,
                    i.getId(),
                    "PAY",
                    -i.quantity,
                    -i.quantity,
                    "pay:" + i.getId(),
                    "Verified payment",
                    true);
            i.stockState = "CONSUMED";
        }
        o.orderStatus = "PAID";
        o.successfulPaymentId = paymentId;
        o.paidAt = clock.instant();
        o.cancelRequested = false;
        tasks.enqueue(
                "ORDER_PAID",
                o.getId().toString(),
                s.encode(Map.of("orderId", o.getId().toString(), "itemId", i.getId().toString())),
                clock.instant());
        if (!i.type.equals("BOOK"))
            tasks.enqueue("ENTITLEMENT_DELIVER", o.getId().toString(), "{}", clock.instant());
        s.systemAudit(
                "ORDER_PAID",
                "ORDER",
                o.getId(),
                "Verified payment confirmed",
                Map.of("amountFen", o.payableAmountFen));
    }

    public void close(StoreOrder o) {
        if (o.orderStatus.equals("CANCELLED")) return;
        if (!o.orderStatus.equals("PENDING_PAYMENT"))
            throw CommerceSupport.conflict("INVALID_STATE", "Paid order cannot be cancelled");
        var i = item(o.getId());
        if (i.stockState.equals("RESERVED")) {
            inventory.change(
                    i.productId,
                    i.getId(),
                    "RELEASE",
                    0,
                    -i.quantity,
                    "release:" + i.getId(),
                    "Order closed after payment resolution",
                    true);
            i.stockState = "RELEASED";
        }
        o.orderStatus = "CANCELLED";
        o.cancelRequested = false;
        s.systemAudit(
                "ORDER_CANCELLED",
                "ORDER",
                o.getId(),
                "Payment absence or closure confirmed",
                Map.of("status", o.orderStatus));
    }

    public void complete(StoreOrder o) {
        requireFinancialClear(o);
        if (o.orderStatus.equals("COMPLETED")) return;
        if (!o.orderStatus.equals("PAID"))
            throw CommerceSupport.conflict("INVALID_STATE", "Order is not paid");
        o.orderStatus = "COMPLETED";
        o.completedAt = clock.instant();
        tasks.enqueue("ORDER_COMPLETED", o.getId().toString(), "{}", clock.instant());
        s.systemAudit(
                "ORDER_COMPLETED",
                "ORDER",
                o.getId(),
                "Fulfillment confirmed",
                Map.of("status", o.orderStatus));
    }
}
