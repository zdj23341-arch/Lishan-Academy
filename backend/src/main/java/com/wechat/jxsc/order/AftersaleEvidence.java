package com.wechat.jxsc.order;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "aftersale_evidence")
public class AftersaleEvidence extends BaseEntity {
    public Long aftersaleId;
    public Long assetId;
}
