package com.wechat.jxsc.membership;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.order.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.time.Instant;

@Component
public class EntitlementRefundPreview implements RefundPreviewContributor {
    private final OrderService orders;
    private final EntitlementLedger ledger;
    private final EntitlementAccess access;
    private final CommerceSupport support;

    public EntitlementRefundPreview(
            OrderService orders,
            EntitlementLedger ledger,
            EntitlementAccess access,
            CommerceSupport support) {
        this.orders = orders;
        this.ledger = ledger;
        this.access = access;
        this.support = support;
    }

    public String name() {
        return "entitlement";
    }

    public record Effect(
            String effect, String type, Instant startsAt, Instant expiresAt, int survivingGrants) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public JsonNode preview(long id, long goods, long freight, boolean fullRefund) {
        var o = orders.lock(id);
        var item = orders.item(id);
        if (item.type.equals("BOOK"))
            return support.node(new Effect("NOT_APPLICABLE", "BOOK", null, null, 0));
        ledger.lock(o.userId);
        boolean full = o.refundedGoodsFen + goods == o.goodsAmountFen;
        String source = "ORDER_ITEM:" + item.getId();
        if (item.type.equals("MEMBERSHIP")) {
            var intervals = ledger.replay(o.userId, full ? source : null);
            Instant start = null, end = null;
            for (var v : intervals) {
                if (end == null || v.startsAt().isAfter(end)) start = v.startsAt();
                end = v.expiresAt();
            }
            return support.node(
                    new Effect(
                            full ? "REVOKE_SOURCE_AND_REPLAY" : "RETAIN",
                            "MEMBER_PAPER",
                            start,
                            end,
                            intervals.size()));
        }
        long course =
                Long.parseLong(
                        support.decode(item.fulfillmentSnapshotJson).path("courseId").asText());
        var right = access.course(o.userId, course, true, full ? source : null);
        return support.node(
                new Effect(
                        full ? "REVOKE_SOURCE" : "RETAIN",
                        "COURSE_VIEW",
                        right.startsAt(),
                        right.expiresAt(),
                        right.status().equals("NONE") ? 0 : 1));
    }
}
