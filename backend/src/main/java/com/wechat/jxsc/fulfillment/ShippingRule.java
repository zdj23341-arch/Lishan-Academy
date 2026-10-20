package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "shipping_rule")
public class ShippingRule extends BaseEntity {
    public String provinceCode;
    public boolean enabled;
    public long freightFen;
    public Long freeThresholdFen;
}
