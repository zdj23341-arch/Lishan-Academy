package com.wechat.jxsc.payment.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class WechatSettings {
    private final Environment env;

    public WechatSettings(Environment env) {
        this.env = env;
    }

    public String get(String key) {
        return env.getProperty("jxsc.wechat." + key, "");
    }

    public boolean enabled(String key) {
        return Boolean.parseBoolean(get(key));
    }

    public String appId() {
        return get("app-id");
    }

    public String merchantId() {
        return get("merchant-id");
    }

    public long transferMax() {
        return env.getProperty("jxsc.wechat.transfer.max-fen", Long.class, 100000L);
    }

    public String paymentPath(String type) {
        if ("BOOK".equals(type)) {
            if (!enabled("pay.book-jsapi-approved"))
                throw unavailable("WECHAT_BOOK_PAYMENT_NOT_APPROVED");
            return "BOOK_JSAPI";
        }
        if (("COURSE".equals(type) || "MEMBERSHIP".equals(type))
                && "JSAPI_VERIFIED".equals(get("pay.digital-path"))
                && !get("pay.digital-approval-reference").isBlank())
            return "DIGITAL_JSAPI_VERIFIED";
        throw unavailable("WECHAT_DIGITAL_PRODUCT_NOT_APPROVED");
    }

    public void validatePath(String path) {
        if ("BOOK_JSAPI".equals(path)) paymentPath("BOOK");
        else if ("DIGITAL_JSAPI_VERIFIED".equals(path)) paymentPath("COURSE");
        else throw unavailable("WECHAT_PRODUCT_PATH_UNAVAILABLE");
    }

    public String notifyUrl(String kind) {
        String url = get("pay.notify-base-url");
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (Exception e) {
            throw unavailable("WECHAT_CALLBACK_NOT_CONFIGURED");
        }
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || url.endsWith("/")) throw unavailable("WECHAT_CALLBACK_NOT_CONFIGURED");
        return url + "/api/v1/webhooks/wechat/" + kind;
    }

    public String responseSerial(WechatCrypto crypto) {
        String mode = get("pay.response-signing-mode"), id = get("pay.response-key-id");
        if (mode.equals("CERTIFICATE") && id.isBlank()) return null;
        if (mode.equals("PUBLIC_KEY") && id.startsWith("PUB_KEY_ID_") && crypto.trusts(id))
            return id;
        throw unavailable("WECHAT_RESPONSE_SIGNING_NOT_CONFIGURED");
    }
}
