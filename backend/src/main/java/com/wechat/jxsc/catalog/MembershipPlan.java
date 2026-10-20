package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "membership_plan")
public class MembershipPlan extends BaseEntity {
    public Long productId;
    public String levelCode;
    public int durationMonths;
    public String status;
}
