package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "inventory_ledger")
public class InventoryLedger extends BaseEntity {
    public Long productId;
    public Long orderItemId;
    public String eventType;
    public int onHandDelta;
    public int reservedDelta;
    public int onHandBefore;
    public int onHandAfter;
    public int reservedBefore;
    public int reservedAfter;
    public String businessKey;
    public String reason;
}
