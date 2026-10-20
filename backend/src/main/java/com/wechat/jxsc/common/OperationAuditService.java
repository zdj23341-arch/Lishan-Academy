package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.auth.CurrentActor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class OperationAuditService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.ClosedAccountPrivacy privacy;

    @PersistenceContext private EntityManager em;
    private final ObjectMapper json;
    private final Clock clock;

    public OperationAuditService(ObjectMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /**
     * Call inside the business transaction; supply safe summaries, never credentials or sensitive
     * raw payloads.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            CurrentActor actor,
            String action,
            String resourceType,
            String resourceId,
            Object before,
            Object after,
            String reason,
            String requestId) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "An audit reason is required");
        var log = new OperationAuditLog();
        log.actorType = actor.type().name();
        log.actorId = actor.id();
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.beforeJson = before == null ? null : json.valueToTree(before);
        log.afterJson = after == null ? null : json.valueToTree(after);
        log.reason = reason;
        log.requestId = requestId;
        log.createdAt = clock.instant();
        log.updatedAt = log.createdAt;
        em.persist(log);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendSystem(
            String action, String resourceType, String resourceId, Object after, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("Audit reason required");
        var log = new OperationAuditLog();
        log.actorType = "SYSTEM";
        log.actorId = 0L;
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.afterJson = json.valueToTree(after);
        log.reason = reason;
        log.requestId = "task-" + java.util.UUID.randomUUID();
        log.createdAt = clock.instant();
        log.updatedAt = log.createdAt;
        em.persist(log);
    }

    public record AuditView(
            String id,
            String actorType,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            JsonNode before,
            JsonNode after,
            String reason,
            String requestId,
            Instant createdAt) {}

    @Transactional(readOnly = true)
    public PageResult<AuditView> list(PageQuery page) {
        var logs =
                em.createQuery(
                                "select a from OperationAuditLog a order by a.createdAt desc,a.id"
                                        + " desc",
                                OperationAuditLog.class)
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList();
        var items =
                logs.stream()
                        .map(
                                a -> {
                                    boolean closed =
                                            privacy.auditClosed(
                                                    a.actorType,
                                                    a.actorId,
                                                    a.resourceType,
                                                    a.resourceId);
                                    return new AuditView(
                                            a.id.toString(),
                                            a.actorType,
                                            a.actorId.toString(),
                                            a.action,
                                            a.resourceType,
                                            a.resourceId,
                                            closed ? null : privacy.sanitize(a.beforeJson),
                                            closed ? null : privacy.sanitize(a.afterJson),
                                            closed ? "Retained account record" : a.reason,
                                            a.requestId,
                                            a.createdAt);
                                })
                        .toList();
        long total =
                em.createQuery("select count(a) from OperationAuditLog a", Long.class)
                        .getSingleResult();
        return new PageResult<>(items, page.page(), page.pageSize(), total);
    }
}
