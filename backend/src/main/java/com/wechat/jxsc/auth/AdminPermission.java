package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "admin_permission")
class AdminPermission extends BaseEntity {
    @Column(nullable = false, length = 64)
    String code;

    protected AdminPermission() {}
}
