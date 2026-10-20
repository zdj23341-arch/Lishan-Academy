package com.wechat.jxsc.order;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "aftersale_case")
public class AftersaleCase extends BaseEntity {
    public String caseNo;
    public Long orderId;
    public Long userId;
    public String type;
    public String status;
    public String reason;
    public long requestedGoodsFen;
    public long requestedFreightFen;
    public Long approvedGoodsFen;
    public Long approvedFreightFen;
    public int returnedQuantity;
    public int receivedQuantity;
    public String returnAddressCipher;
    public String returnCarrierCode;
    public String returnTrackingNo;
    public Instant receivedAt;
}
