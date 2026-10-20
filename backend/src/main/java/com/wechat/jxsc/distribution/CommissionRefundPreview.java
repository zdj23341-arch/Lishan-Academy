package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.order.*;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CommissionRefundPreview implements RefundPreviewContributor {
    private final FinanceStore f;
    private final CommerceSupport s;
    private final OrderService orders;

    public CommissionRefundPreview(FinanceStore f, CommerceSupport s, OrderService orders) {
        this.f = f;
        this.s = s;
        this.orders = orders;
    }

    public String name() {
        return "commission";
    }

    public JsonNode preview(long orderId, long goods, long freight, boolean full) {
        var o = orders.lock(orderId);
        orders.requireFinancialClear(o);
        var rows = f.db.queryForList("select * from attribution where order_id=?", orderId);
        if (rows.isEmpty()) return s.node(Map.of("effect", "NOT_APPLICABLE", "reversalFen", 0));
        var a = rows.getFirst();
        long
                current =
                        CommissionService.commission(
                                Math.max(0, n(a, "base_fen") - o.refundedGoodsFen),
                                n(a, "rate_bps")),
                after =
                        CommissionService.commission(
                                Math.max(0, n(a, "base_fen") - o.refundedGoodsFen - goods),
                                n(a, "rate_bps"));
        return s.node(
                Map.of(
                        "effect",
                        "CUMULATIVE_REVERSAL",
                        "reversalFen",
                        current - after,
                        "remainingFen",
                        after,
                        "distributorId",
                        "" + n(a, "distributor_id")));
    }
}
