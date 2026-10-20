package com.wechat.jxsc.order;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class AdminOrderQuery {
    @PersistenceContext private EntityManager em;
    private final OrderService orders;

    public AdminOrderQuery(OrderService orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public PageResult<OrderDtos.View> list(
            PageQuery page,
            String number,
            String orderStatus,
            String refundStatus,
            String fulfillmentStatus,
            Instant from,
            Instant to) {
        if (number != null && number.length() > 64
                || from != null && to != null && !to.isAfter(from))
            throw CommerceSupport.invalid("Invalid order filter");
        StringBuilder where = new StringBuilder(" where 1=1");
        Map<String, Object> params = new HashMap<>();
        add(
                where,
                params,
                "orderStatus",
                orderStatus,
                Set.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED"));
        add(
                where,
                params,
                "refundStatus",
                refundStatus,
                Set.of(
                        "NONE",
                        "REQUESTED",
                        "RETURN_REQUIRED",
                        "RETURNING",
                        "PROCESSING",
                        "PARTIAL",
                        "FULL",
                        "REJECTED",
                        "FAILED"));
        add(
                where,
                params,
                "fulfillmentStatus",
                fulfillmentStatus,
                Set.of("NOT_REQUIRED", "PENDING_SHIPMENT", "SHIPPED", "RECEIVED"));
        if (number != null && !number.isBlank()) {
            where.append(" and o.orderNo=:number");
            params.put("number", number);
        }
        if (from != null) {
            where.append(" and o.createdAt>=:fromTime");
            params.put("fromTime", from);
        }
        if (to != null) {
            where.append(" and o.createdAt<:toTime");
            params.put("toTime", to);
        }
        var q =
                em.createQuery(
                        "select o from StoreOrder o"
                                + where
                                + " order by o.createdAt desc,o.id desc",
                        StoreOrder.class);
        var c = em.createQuery("select count(o) from StoreOrder o" + where, Long.class);
        params.forEach(
                (k, v) -> {
                    q.setParameter(k, v);
                    c.setParameter(k, v);
                });
        return new PageResult<>(
                q
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList()
                        .stream()
                        .map(orders::view)
                        .toList(),
                page.page(),
                page.pageSize(),
                c.getSingleResult());
    }

    private void add(
            StringBuilder where,
            Map<String, Object> params,
            String field,
            String value,
            Set<String> allowed) {
        if (value != null) {
            if (!allowed.contains(value)) throw CommerceSupport.invalid("Invalid " + field);
            where.append(" and o.").append(field).append("=:").append(field);
            params.put(field, value);
        }
    }
}
