package com.wechat.jxsc.payment.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.common.ApiException;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

public final class WechatProtocol {
    private WechatProtocol() {}

    public static ApiException failure(String code) {
        // Never propagate provider bodies, request URLs, cryptographic material or exception
        // causes.
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                code,
                "WeChat response could not be accepted; recover using the original business"
                        + " number");
    }

    public static ApiException unavailable(String code) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                code,
                "Required WeChat capability is not configured");
    }

    public static String text(JsonNode n, String key) {
        var v = n.path(key);
        if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > 8192)
            throw failure("WECHAT_INVALID_RESPONSE");
        return v.asText();
    }

    public static long amount(JsonNode n, String key) {
        var v = n.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0)
            throw failure("WECHAT_INVALID_RESPONSE");
        return v.longValue();
    }

    public static void equal(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw failure("WECHAT_IDENTITY_MISMATCH");
    }

    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String path(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
