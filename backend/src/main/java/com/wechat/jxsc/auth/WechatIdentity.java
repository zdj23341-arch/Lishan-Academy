package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_wechat_identity")
class WechatIdentity extends BaseEntity {
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    UserAccount user;

    @Column(nullable = false, length = 128)
    String appId;

    @Column(nullable = false, length = 128)
    String openid;

    @Column(length = 128)
    String unionid;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant lastLoginAt;

    protected WechatIdentity() {}
}
