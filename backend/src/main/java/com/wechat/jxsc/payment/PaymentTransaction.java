package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction extends BaseEntity {
    public String appId;
    public String merchantId;
    public Long orderId;
    public String outTradeNo;
    public String channel;
    public String channelTxnId;
    public String state;
    public boolean refundRequired;
    public long amountFen;
    public Instant paidAt;
    public String paramsJson;
    public Instant dispatchStartedAt;

    @Column(columnDefinition = "text")
    public String payerCipher;

    public String description;
    public String productPath;

    @Column(columnDefinition = "text")
    public String paramsCipher;
}
