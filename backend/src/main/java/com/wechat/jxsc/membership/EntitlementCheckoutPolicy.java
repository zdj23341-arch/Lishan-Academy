package com.wechat.jxsc.membership;

import com.wechat.jxsc.catalog.CheckoutCatalog;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.order.CheckoutPolicy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class EntitlementCheckoutPolicy implements CheckoutPolicy {
    private final EntitlementLedger ledger;
    private final EntitlementAccess access;

    public EntitlementCheckoutPolicy(EntitlementLedger ledger, EntitlementAccess access) {
        this.ledger = ledger;
        this.access = access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void validate(long user, CheckoutCatalog.Snapshot product) {
        if (product.type().equals("BOOK")) return;
        ledger.ensureSubject(user);
        ledger.lock(user);
        if (product.type().equals("COURSE")
                && access.course(
                                user,
                                Long.parseLong(product.fulfillment().get("courseId").toString()),
                                true)
                        .active())
            throw CommerceSupport.conflict(
                    "ALREADY_PURCHASED", "An active course purchase already exists");
    }
}
