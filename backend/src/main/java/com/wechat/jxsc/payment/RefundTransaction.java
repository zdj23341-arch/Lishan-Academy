package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "refund_transaction")
public class RefundTransaction extends BaseEntity {
    public boolean dispatchSuppressed;
    public Long aftersaleId;
    public Long orderId;
    public Long paymentId;
    public String outRefundNo;
    public String channelRefundId;
    public String channel;
    public long goodsFen;
    public long freightFen;
    public String state;
    public String reason;
    public Instant successAt;
    public Instant dispatchStartedAt;
    public int restockQuantity;
    public boolean exceptional;
}
