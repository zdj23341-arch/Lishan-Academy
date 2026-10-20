package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shipment")
public class Shipment extends BaseEntity {
    public Long orderId;
    public int shippedQuantity;
    public String carrierCode;
    public String trackingNo;
    public Instant shippedAt;
    public Instant receivedAt;
    public String status;
    public Instant nextSyncAt;
    public boolean simulated;

    @Column(columnDefinition = "text")
    public String eventsJson;
}
