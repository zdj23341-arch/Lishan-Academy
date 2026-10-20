package com.wechat.jxsc.common;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data) {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return new ApiResponse<>("OK", "OK", data, RequestIdFilter.requestId(attributes.getRequest()));
    }
}
