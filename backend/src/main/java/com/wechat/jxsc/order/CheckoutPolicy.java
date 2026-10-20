package com.wechat.jxsc.order;

import com.wechat.jxsc.catalog.CheckoutCatalog;

/**
 * Task 5 installs entitlement-aware purchase checks. Called within order creation under the
 * customer lock.
 */
public interface CheckoutPolicy {
    void validate(long userId, CheckoutCatalog.Snapshot product);
}
