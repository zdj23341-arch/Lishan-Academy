package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "admin_role")
class AdminRole extends BaseEntity {
    @Column(nullable = false, length = 64)
    String code;

    @ManyToMany
    @JoinTable(
            name = "admin_role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    Set<AdminPermission> permissions = new HashSet<>();

    protected AdminRole() {}
}
