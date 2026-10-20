package com.wechat.jxsc.analytics;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.distribution.FinanceStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DownloadSourceClassification {
    private final FinanceStore f;

    public DownloadSourceClassification(FinanceStore f) {
        this.f = f;
    }

    /**
     * The caller's READ COMMITTED connection gives a fresh nonlocking read after its subject wait.
     * Never borrow another connection or take an order lock while authorization holds its subject.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String classify(long item) {
        var rows =
                f.db.queryForList(
                        "select o.channel order_channel,p.channel payment_channel,p.state"
                            + " payment_state,o.successful_payment_id from order_item i join"
                            + " store_order o on o.id=i.order_id left join payment_transaction p on"
                            + " p.id=o.successful_payment_id where i.id=?",
                        item);
        if (rows.isEmpty()) return "UNPROVEN";
        var r = rows.getFirst();
        if ("NO_CHARGE".equals(str(r, "order_channel"))) return "NO_CHARGE";
        if ("DEV".equals(str(r, "order_channel")) || "DEV".equals(str(r, "payment_channel")))
            return "DEV_PAYMENT";
        return "SUCCEEDED".equals(str(r, "payment_state")) ? "NON_DEV_PAYMENT" : "UNPROVEN";
    }
}
