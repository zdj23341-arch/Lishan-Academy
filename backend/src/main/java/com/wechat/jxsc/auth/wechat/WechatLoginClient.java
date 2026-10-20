package com.wechat.jxsc.auth.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.payment.wechat.WechatProtocol;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;

public final class WechatLoginClient {
    private final URI origin;
    private final String appId, secret;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public WechatLoginClient(URI origin, String appId, String secret, ObjectMapper json) {
        this.origin = origin;
        this.appId = appId;
        this.secret = secret;
        this.json = json;
    }

    public record Identity(String appId, String openid, String unionid) {
        @Override
        public String toString() {
            return "WechatIdentity[redacted]";
        }
    }

    public Identity exchange(String code) {
        if (secret.isBlank() || appId.isBlank())
            throw WechatProtocol.unavailable("WECHAT_LOGIN_NOT_CONFIGURED");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Login transport cannot run in a transaction");
        try {
            String query =
                    "appid="
                            + enc(appId)
                            + "&secret="
                            + enc(secret)
                            + "&js_code="
                            + enc(code)
                            + "&grant_type=authorization_code";
            var r =
                    com.wechat.jxsc.payment.wechat.WechatResponseBody.send(
                            http,
                            HttpRequest.newBuilder(origin.resolve("/sns/jscode2session?" + query))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            65536);
            byte[] bytes = r.body();
            if (r.statusCode() != 200 || bytes.length > 65536)
                throw WechatProtocol.failure("WECHAT_LOGIN_UNAVAILABLE");
            var n = json.readTree(bytes);
            if (n.has("errcode")
                    && (!n.path("errcode").isIntegralNumber() || n.path("errcode").asInt() != 0))
                throw new com.wechat.jxsc.common.ApiException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "WECHAT_LOGIN_REJECTED",
                        "WeChat login was rejected; request a fresh login code");
            String openid = WechatProtocol.text(n, "openid"),
                    key = WechatProtocol.text(n, "session_key");
            if (openid.length() > 128 || Base64.getDecoder().decode(key).length != 16)
                throw WechatProtocol.failure("WECHAT_LOGIN_INVALID_RESPONSE");
            String union = n.has("unionid") ? WechatProtocol.text(n, "unionid") : null;
            if (union != null && union.length() > 128)
                throw WechatProtocol.failure("WECHAT_LOGIN_INVALID_RESPONSE");
            return new Identity(appId, openid, union);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw WechatProtocol.failure("WECHAT_LOGIN_UNAVAILABLE");
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw WechatProtocol.failure("WECHAT_LOGIN_INVALID_RESPONSE");
        }
    }

    private String enc(String s) {
        return URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
