package com.wechat.jxsc.common.content;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;

import jakarta.persistence.EntityManager;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

import java.util.Map;

@Component
public class ContentSupport {
    private final OperationAuditService audit;

    public ContentSupport(OperationAuditService audit) {
        this.audit = audit;
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content not found");
    }

    public static ApiException stale() {
        return new ApiException(
                HttpStatus.CONFLICT, "VERSION_CONFLICT", "Content changed; reload before editing");
    }

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid("Invalid ID");
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid("Invalid ID");
        }
    }

    public static void version(long actual, Long expected) {
        if (expected == null) throw invalid("Version required");
        if (actual != expected) throw stale();
    }

    public static <T> T require(EntityManager em, Class<T> type, long id) {
        T value = em.find(type, id);
        if (value == null) throw missing();
        return value;
    }

    public void audit(String action, String type, long id, long before, long after, String reason) {
        var attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        audit.append(
                CurrentActor.require(),
                action,
                type,
                Long.toString(id),
                before < 0 ? null : Map.of("version", before),
                Map.of("version", after),
                reason,
                attributes == null
                        ? "DEV-SEED"
                        : RequestIdFilter.requestId(attributes.getRequest()));
    }

    public record Lifecycle(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero
                    Long version,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500)
                    String reason) {}
}
