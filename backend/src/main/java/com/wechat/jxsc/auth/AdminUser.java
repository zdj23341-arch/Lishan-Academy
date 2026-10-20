package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "admin_user")
class AdminUser extends BaseEntity {
    @Column(nullable = false, length = 100)
    String loginName;

    @Column(nullable = false, length = 100)
    String passwordHash;

    @Column(nullable = false, length = 32)
    String status = "ACTIVE";

    @Column(nullable = false)
    boolean passwordChangeRequired = true;

    @Column(columnDefinition = "datetime(6)")
    Instant lastLoginAt;

    @ManyToMany
    @JoinTable(
            name = "admin_user_role",
            joinColumns = @JoinColumn(name = "admin_user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    Set<AdminRole> roles = new HashSet<>();

    protected AdminUser() {}
}
