package com.wechat.jxsc.order;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "order_quote")
public class OrderQuote extends BaseEntity {
    public String tokenHash;
    public Long orderId;
    public Long userId;
    public Long productId;
    public Long addressId;
    public Long addressVersion;
    public Long shippingRuleId;
    public Long shippingRuleVersion;
    public int quantity;
    public long productVersion;
    public Long termsVersion;
    public long goodsAmountFen;
    public long freightAmountFen;
    public Instant expiresAt;

    @Column(columnDefinition = "text")
    public String snapshotJson;
}
