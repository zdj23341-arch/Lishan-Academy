package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "login_session")
class LoginSession extends BaseEntity {
    @Column(nullable = false, length = 64)
    String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    CurrentActor.Type actorType;

    @Column(nullable = false)
    Long actorId;

    Long userId;
    Long adminUserId;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant expiresAt;

    @Column(columnDefinition = "datetime(6)")
    Instant revokedAt;

    @Column(length = 64)
    String deviceHash;

    protected LoginSession() {}
}
