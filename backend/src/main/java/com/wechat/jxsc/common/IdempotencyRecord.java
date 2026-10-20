package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "idempotency_record")
class IdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 16)
    String actorType;

    @Column(nullable = false)
    Long actorId;

    @Column(nullable = false, length = 64)
    String scope;

    @Column(nullable = false, length = 36)
    String operationKey;

    @Column(nullable = false, length = 64)
    String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    IdempotencyService.State state;

    @JdbcTypeCode(SqlTypes.JSON)
    JsonNode responseJson;

    @Column(length = 64)
    String resourceId;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant expiresAt;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant createdAt;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant updatedAt;

    protected IdempotencyRecord() {}
}
