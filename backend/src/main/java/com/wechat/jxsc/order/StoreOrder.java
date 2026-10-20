package com.wechat.jxsc.order;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "store_order")
public class StoreOrder extends BaseEntity {
    public String orderNo;
    public Long userId;
    public Long quoteId;
    public String channel;
    public String orderStatus;
    public String refundStatus;
    public String fulfillmentStatus;
    public long goodsAmountFen;
    public long freightAmountFen;
    public long payableAmountFen;
    public long refundedGoodsFen;
    public long refundedFreightFen;
    public Instant expiresAt;
    public Instant paidAt;
    public Instant completedAt;
    public boolean cancelRequested;
    public boolean financialHold;
    public Long successfulPaymentId;
    public Long activeAftersaleId;

    @Column(columnDefinition = "text")
    public String attributionSnapshotJson;
}
