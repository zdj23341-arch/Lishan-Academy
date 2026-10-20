package com.wechat.jxsc.common.commerce;

import com.fasterxml.jackson.databind.*;
import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;

import jakarta.persistence.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Supplier;

@Component
public class CommerceSupport {
    @PersistenceContext private EntityManager em;
    private final ObjectMapper json;

    public ObjectMapper mapper() {
        return json;
    }

    private final IdempotencyService idempotency;
    private final OperationAuditService audit;
    private final ProtectedOperationResults protectedResults;

    public CommerceSupport(
            ObjectMapper json,
            IdempotencyService idempotency,
            OperationAuditService audit,
            ProtectedOperationResults protectedResults) {
        this.json = json;
        this.idempotency = idempotency;
        this.audit = audit;
        this.protectedResults = protectedResults;
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    public static ApiException stale() {
        return conflict(
                "ORDER_STALE",
                "Selection changed or quote expired; request a new /orders/preview with the"
                        + " original selection");
    }

    public static long id(String value) {
        try {
            long n = Long.parseLong(value);
            if (n <= 0) throw invalid("Invalid ID");
            return n;
        } catch (NumberFormatException ex) {
            throw invalid("Invalid ID");
        }
    }

    public <T> T lock(Class<T> type, long id) {
        T e = em.find(type, id);
        // Refresh directly before upgrading: another transaction may have advanced a managed
        // version while this command waited. A find-with-lock upgrade can fail before refresh.
        if (e != null) em.refresh(e, LockModeType.PESSIMISTIC_WRITE);
        else e = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (e == null) throw missing();
        return type.cast(org.hibernate.Hibernate.unproxy(e));
    }

    public JsonNode node(Object value) {
        return json.valueToTree(value);
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode decode(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public JsonNode execute(String scope, String key, Object request, Supplier<Object> work) {
        return protectedResults.reveal(
                idempotency
                        .execute(
                                CurrentActor.require(),
                                scope,
                                key,
                                node(request),
                                () -> {
                                    Object value = work.get();
                                    JsonNode response = node(value);
                                    return new IdempotencyService.Outcome(
                                            IdempotencyService.State.SUCCEEDED,
                                            response.path("id").asText(null),
                                            protectedResults.protect(response));
                                })
                        .response());
    }

    public void audit(String action, String resource, long id, String reason, Object after) {
        audit(action, resource, id, reason, null, after);
    }

    public void audit(
            String action, String resource, long id, String reason, Object before, Object after) {
        audit.append(
                CurrentActor.require(),
                action,
                resource,
                Long.toString(id),
                before,
                after,
                reason,
                requestId());
    }

    public void systemAudit(String action, String resource, long id, String reason, Object after) {
        audit.appendSystem(action, resource, Long.toString(id), after, reason);
    }

    private String requestId() {
        var a = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return a instanceof org.springframework.web.context.request.ServletRequestAttributes s
                ? RequestIdFilter.requestId(s.getRequest())
                : "internal-" + UUID.randomUUID();
    }
}
