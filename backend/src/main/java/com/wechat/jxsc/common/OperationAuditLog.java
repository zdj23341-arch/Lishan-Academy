package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "operation_audit_log")
class OperationAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 16)
    String actorType;

    @Column(nullable = false)
    Long actorId;

    @Column(nullable = false, length = 64)
    String action;

    @Column(nullable = false, length = 64)
    String resourceType;

    @Column(nullable = false, length = 64)
    String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    JsonNode beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    JsonNode afterJson;

    @Column(nullable = false, length = 500)
    String reason;

    @Column(nullable = false, length = 128)
    String requestId;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant createdAt;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    Instant updatedAt;

    protected OperationAuditLog() {}
}
