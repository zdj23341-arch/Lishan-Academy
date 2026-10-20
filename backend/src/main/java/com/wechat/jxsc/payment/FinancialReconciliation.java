package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "financial_reconciliation")
public class FinancialReconciliation extends BaseEntity {
    public Long orderId;
    public String resourceType;
    public Long transactionId;
    public Long firstInboxId;
    public String reason;
    public String status;
    public String lastQueryState;
    public String channelId;
    public boolean successObserved;
    public long amountFen;
}
