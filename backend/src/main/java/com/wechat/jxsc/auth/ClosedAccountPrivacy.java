package com.wechat.jxsc.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Ordinary views never reveal retained personal data, including old idempotency responses. */
@Component
public class ClosedAccountPrivacy {
    private final JdbcTemplate db;

    public ClosedAccountPrivacy(JdbcTemplate db) {
        this.db = db;
    }

    public boolean closed(long user) {
        return db.queryForObject(
                        "select count(*) from account_closure where user_id=?", Long.class, user)
                > 0;
    }

    public boolean orderClosed(long order) {
        return db.queryForObject(
                        "select count(*) from store_order o join account_closure c on"
                                + " c.user_id=o.user_id where o.id=?",
                        Long.class,
                        order)
                > 0;
    }

    /** Called with the order already locked, so an old RR snapshot cannot revive provider data. */
    public boolean orderClosedCurrent(long order) {
        return !db.queryForList(
                        "select c.user_id from store_order o join account_closure c on"
                                + " c.user_id=o.user_id where o.id=? for share",
                        Long.class,
                        order)
                .isEmpty();
    }

    public boolean auditClosed(String actorType, long actor, String resourceType, String resource) {
        if ("CUSTOMER".equals(actorType) && closed(actor)) return true;
        long id;
        try {
            id = Long.parseLong(resource);
        } catch (Exception ignored) {
            return false;
        }
        return switch (resourceType) {
            case "USER" -> closed(id);
            case "ORDER" -> orderClosed(id);
            case "AFTERSALE" ->
                    db.queryForObject(
                                    "select count(*) from aftersale_case a join account_closure c"
                                            + " on c.user_id=a.user_id where a.id=?",
                                    Long.class,
                                    id)
                            > 0;
            case "DISTRIBUTOR" ->
                    db.queryForObject(
                                    "select count(*) from distributor d join account_closure c on"
                                            + " c.user_id=d.user_id where d.id=?",
                                    Long.class,
                                    id)
                            > 0;
            case "WITHDRAWAL" ->
                    db.queryForObject(
                                    "select count(*) from withdrawal_request w join distributor d"
                                            + " on d.id=w.distributor_id join account_closure c on"
                                            + " c.user_id=d.user_id where w.id=?",
                                    Long.class,
                                    id)
                            > 0;
            default -> false;
        };
    }

    public JsonNode sanitize(JsonNode node) {
        if (node == null) return null;
        var result = node.deepCopy();
        sanitizeTree(result, false);
        return result;
    }

    private void sanitizeTree(JsonNode node, boolean inherited) {
        boolean closed = inherited;
        if (node.isObject()) {
            if (node.hasNonNull("orderId")) closed |= orderClosed(node.path("orderId").asLong());
            if (node.hasNonNull("orderNo") && node.hasNonNull("id"))
                closed |= orderClosed(node.path("id").asLong());
            if (node.hasNonNull("userId")) closed |= closed(node.path("userId").asLong());
            if (closed) {
                var object = (ObjectNode) node;
                for (String field :
                        List.of(
                                "address",
                                "name",
                                "phone",
                                "detail",
                                "nickname",
                                "avatarUrl",
                                "reason",
                                "returnAddress",
                                "returnTrackingNo",
                                "returnTracking",
                                "trackingNo",
                                "evidenceAssetIds",
                                "evidence",
                                "recipientName"))
                    if (object.has(field))
                        object.set(
                                field,
                                object.has(field) && object.get(field).isArray()
                                        ? com.fasterxml.jackson.databind.node.JsonNodeFactory
                                                .instance
                                                .arrayNode()
                                        : com.fasterxml.jackson.databind.node.NullNode.instance);
            }
        }
        for (var child : node) sanitizeTree(child, closed);
    }
}
