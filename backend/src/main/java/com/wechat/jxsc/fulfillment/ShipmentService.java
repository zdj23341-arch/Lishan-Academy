package com.wechat.jxsc.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import jakarta.persistence.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class ShipmentService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.ClosedAccountPrivacy privacy;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    @PersistenceContext private EntityManager em;
    private final OrderService orders;
    private final CommerceSupport s;
    private final TaskQueue tasks;
    private final Clock clock;
    private final Duration cadence;
    private final Duration autoReceiveWindow;
    private final CarrierCatalog carriers;
    private final AddressService addresses;

    public Duration cadence() {
        return cadence;
    }

    public ShipmentService(
            OrderService orders,
            CommerceSupport s,
            TaskQueue tasks,
            Clock clock,
            Environment env,
            AddressService addresses,
            CarrierCatalog carriers) {
        this.orders = orders;
        this.s = s;
        this.tasks = tasks;
        this.clock = clock;
        this.addresses = addresses;
        this.carriers = carriers;
        cadence =
                Duration.ofSeconds(
                        Math.max(
                                1800,
                                env.getProperty(
                                        "jxsc.logistics.query-interval-seconds",
                                        Long.class,
                                        1800L)));
        autoReceiveWindow =
                Duration.ofDays(
                        Math.min(
                                180,
                                Math.max(
                                        1,
                                        env.getProperty(
                                                "jxsc.logistics.auto-receive-days",
                                                Integer.class,
                                                15))));
    }

    private Shipment shipment(long id) {
        var rows =
                em.createQuery("select p from Shipment p where p.orderId=:o", Shipment.class)
                        .setParameter("o", id)
                        .getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public JsonNode ship(long id, FulfillmentDtos.Ship r, String key) {
        return s.execute(
                "SHIP_ORDER",
                key,
                Map.of("orderId", id, "request", r),
                () -> {
                    var o = orders.lock(id);
                    orders.requireFinancialClear(o);
                    carriers.require(r.carrierCode());
                    if (o.getVersion() != r.version())
                        throw CommerceSupport.conflict(
                                "VERSION_CONFLICT", "Reload order before editing shipment");
                    if (!o.orderStatus.equals("PAID")
                            || o.refundStatus.equals("FULL")
                            || o.activeAftersaleId != null
                            || !orders.item(id).type.equals("BOOK")
                            || o.fulfillmentStatus.equals("RECEIVED"))
                        throw CommerceSupport.conflict(
                                "INVALID_STATE", "Order cannot be shipped or edited");
                    var p = shipment(id);
                    if (p == null) {
                        var item = orders.item(id);
                        int remaining = item.quantity - item.restoredQuantity;
                        if (remaining <= 0)
                            throw CommerceSupport.conflict(
                                    "INVALID_STATE", "No remaining copies to ship");
                        p = new Shipment();
                        p.orderId = id;
                        p.shippedQuantity = remaining;
                        p.shippedAt = clock.instant();
                    } else em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
                    em.lock(o, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
                    p.carrierCode = r.carrierCode();
                    p.trackingNo = r.trackingNo();
                    p.status = "SYNCING";
                    p.simulated = false;
                    p.eventsJson = "[]";
                    if (p.nextSyncAt == null || p.nextSyncAt.isBefore(clock.instant()))
                        p.nextSyncAt = clock.instant();
                    o.fulfillmentStatus = "SHIPPED";
                    if (p.getId() == null) em.persist(p);
                    em.flush();
                    tasks.enqueue(
                            "LOGISTICS_SYNC",
                            id + ":" + p.getVersion(),
                            s.encode(
                                    Map.of(
                                            "orderId",
                                            Long.toString(id),
                                            "version",
                                            p.getVersion())),
                            clock.instant());
                    tasks.enqueue(
                            "AUTO_RECEIVE",
                            Long.toString(id),
                            "{}",
                            p.shippedAt.plus(autoReceiveWindow));
                    s.audit(
                            "SHIPMENT_EDIT",
                            "ORDER",
                            id,
                            r.reason(),
                            Map.of("shipmentId", p.getId().toString(), "version", p.getVersion()));
                    return orders.view(o);
                });
    }

    @Transactional
    public JsonNode receive(long id, String key, boolean automatic) {
        if (!automatic) lifecycle.customer();
        var work =
                (java.util.function.Supplier<Object>)
                        () -> {
                            var o = orders.lock(id);
                            orders.requireFinancialClear(o);
                            if (!automatic) CurrentActor.require().requireOwner(o.userId);
                            if (o.refundStatus.equals("FULL")) {
                                if (automatic) return orders.view(o);
                                throw CommerceSupport.conflict(
                                        "INVALID_STATE",
                                        "Fully refunded orders cannot be fulfilled");
                            }
                            if (o.fulfillmentStatus.equals("RECEIVED")) return orders.view(o);
                            if (!o.fulfillmentStatus.equals("SHIPPED"))
                                throw CommerceSupport.conflict(
                                        "INVALID_STATE", "Order has not shipped");
                            if (o.activeAftersaleId != null)
                                throw CommerceSupport.conflict(
                                        "ACTIVE_AFTERSALE",
                                        "Resolve active aftersale before receipt");
                            var p = shipment(id);
                            if (automatic
                                    && p.shippedAt
                                            .plus(autoReceiveWindow)
                                            .isAfter(clock.instant()))
                                throw new TaskHandler.RetryLater(
                                        "RECEIPT_NOT_DUE",
                                        Duration.between(
                                                clock.instant(),
                                                p.shippedAt.plus(autoReceiveWindow)));
                            p.receivedAt = clock.instant();
                            p.status = "RECEIVED";
                            o.fulfillmentStatus = "RECEIVED";
                            orders.complete(o);
                            return orders.view(o);
                        };
        return automatic
                ? s.node(work.get())
                : s.execute("CONFIRM_RECEIPT", key, Map.of("orderId", id), work);
    }

    @Transactional(readOnly = true)
    public FulfillmentDtos.LogisticsView get(long id, boolean admin) {
        boolean closed = privacy.orderClosed(id);
        var o = em.find(StoreOrder.class, id);
        if (o == null) throw CommerceSupport.missing();
        if (!admin) CurrentActor.require().requireOwner(o.userId);
        var p = shipment(id);
        if (p == null) throw CommerceSupport.missing();
        List<FulfillmentDtos.Event> events;
        try {
            events =
                    s.mapper().readerForListOf(FulfillmentDtos.Event.class).readValue(p.eventsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new FulfillmentDtos.LogisticsView(
                p.shippedQuantity,
                p.carrierCode,
                closed ? null : p.trackingNo,
                p.status,
                p.simulated,
                p.shippedAt,
                p.receivedAt,
                closed ? List.of() : events);
    }

    public record Sync(long orderId, long version, String carrier, String tracking, String phone) {
        @Override
        public String toString() {
            return "ShipmentSync[orderId=" + orderId + ", version=" + version + "]";
        }
    }

    @Transactional
    public Sync prepare(long id) {
        orders.lock(id);
        if (privacy.orderClosedCurrent(id)) return null;
        var p = shipment(id);
        if (p == null) return null;
        em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        if (p.receivedAt != null || p.nextSyncAt.isAfter(clock.instant())) return null;
        p.nextSyncAt = clock.instant().plus(cadence);
        em.flush();
        var address = addresses.snapshotView(id);
        return new Sync(
                id,
                p.getVersion(),
                p.carrierCode,
                p.trackingNo,
                address == null ? null : address.phone());
    }

    @Transactional
    public void synced(Sync sync, LogisticsProvider.Tracking result) {
        orders.lock(sync.orderId());
        if (privacy.orderClosedCurrent(sync.orderId())) return;
        var p = shipment(sync.orderId());
        em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        if (p.getVersion() != sync.version() || p.receivedAt != null) return;
        p.status = result.status();
        p.simulated = result.simulated();
        p.eventsJson = s.encode(result.events());
    }

    @Transactional
    public void syncFailed(Sync sync) {
        orders.lock(sync.orderId());
        if (privacy.orderClosedCurrent(sync.orderId())) return;
        var p = shipment(sync.orderId());
        em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        if (p.getVersion() == sync.version() && p.receivedAt == null) p.status = "SYNCING";
    }

    @Transactional(readOnly = true)
    public boolean finished(long id) {
        var order = em.find(StoreOrder.class, id);
        var p = shipment(id);
        return p == null || p.receivedAt != null || order.refundStatus.equals("FULL");
    }
}
