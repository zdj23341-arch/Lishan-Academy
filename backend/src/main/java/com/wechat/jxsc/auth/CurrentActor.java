package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

public record CurrentActor(
        Type type,
        Long id,
        Long sessionId,
        Set<String> permissions,
        boolean passwordChangeRequired) {
    public enum Type {
        CUSTOMER,
        ADMIN
    }

    public CurrentActor {
        permissions = Set.copyOf(permissions);
    }

    public static CurrentActor require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentActor actor))
            throw unauthorized();
        return actor;
    }

    public void requireType(Type expected) {
        if (type != expected)
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    public void requireOwner(Long ownerId) {
        requireType(Type.CUSTOMER);
        if (!id.equals(ownerId))
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }
}
