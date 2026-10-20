package com.wechat.jxsc.common;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Reports property names only. Never interpolate supplied values or secret decoder exceptions. */
public final class ProductionPreflight {
    private ProductionPreflight() {}

    public static List<String> errors(Environment e) {
        var missing = new TreeSet<String>();
        for (String key :
                List.of(
                        "spring.datasource.url",
                        "spring.datasource.username",
                        "spring.datasource.password",
                        "jxsc.wechat.app-id",
                        "jxsc.wechat.login.app-secret",
                        "jxsc.wechat.merchant-id",
                        "jxsc.wechat.pay.merchant-serial",
                        "jxsc.wechat.pay.private-key-file",
                        "jxsc.wechat.pay.api-v3-key",
                        "jxsc.wechat.pay.trusted-public-key-files",
                        "jxsc.wechat.pay.response-signing-mode",
                        "jxsc.wechat.pay.notify-base-url",
                        "jxsc.storage.cos.endpoint",
                        "jxsc.storage.cos.secret-id",
                        "jxsc.storage.cos.secret-key",
                        "jxsc.scanner.host",
                        "jxsc.media.ffprobe",
                        "jxsc.media.ffmpeg",
                        "jxsc.logistics.customer",
                        "jxsc.logistics.key")) if (value(e, key).isBlank()) missing.add(key);
        String data =
                e.getProperty("jxsc.crypto.key-base64", e.getProperty("JXSC_DATA_KEY_BASE64", ""));
        try {
            if (Base64.getDecoder().decode(data).length != 32) missing.add("JXSC_DATA_KEY_BASE64");
        } catch (Exception x) {
            missing.add("JXSC_DATA_KEY_BASE64");
        }
        if (!value(e, "spring.datasource.url").startsWith("jdbc:mysql:"))
            missing.add("spring.datasource.url");
        if (!value(e, "spring.jpa.hibernate.ddl-auto").equals("validate"))
            missing.add("spring.jpa.hibernate.ddl-auto=validate");
        for (String key : List.of("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled"))
            if (e.getProperty(key, Boolean.class, false)) missing.add(key + "=false");
        for (String key :
                List.of(
                        "jxsc.wechat.pay.private-key-file",
                        "jxsc.media.ffprobe",
                        "jxsc.media.ffmpeg")) {
            try {
                if (!Files.isRegularFile(Path.of(value(e, key)))) missing.add(key);
            } catch (Exception x) {
                missing.add(key);
            }
        }
        if (value(e, "jxsc.wechat.pay.api-v3-key")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length
                != 32) missing.add("jxsc.wechat.pay.api-v3-key");
        try {
            var u = URI.create(value(e, "jxsc.wechat.pay.notify-base-url"));
            if (!"https".equals(u.getScheme())
                    || u.getHost() == null
                    || u.getUserInfo() != null
                    || u.getQuery() != null
                    || u.getFragment() != null) missing.add("jxsc.wechat.pay.notify-base-url");
        } catch (Exception x) {
            missing.add("jxsc.wechat.pay.notify-base-url");
        }
        if (!e.getProperty("jxsc.wechat.pay.enabled", Boolean.class, false))
            missing.add("jxsc.wechat.pay.enabled=true (includes original-number recovery)");
        if (e.getProperty("jxsc.production.recovery-only", Boolean.class, false)) {
            if (e.getProperty("jxsc.wechat.transfer.enabled", Boolean.class, false)
                    || e.getProperty("jxsc.wechat.pay.book-jsapi-approved", Boolean.class, false)
                    || !Set.of("", "UNCONFIGURED")
                            .contains(value(e, "jxsc.wechat.pay.digital-path")))
                missing.add("recovery-only requires disabled create capabilities");
        } else {
            for (String key :
                    List.of(
                            "jxsc.wechat.transfer.enabled",
                            "jxsc.wechat.pay.book-jsapi-approved",
                            "jxsc.wechat.statements.enabled"))
                if (!e.getProperty(key, Boolean.class, false)) missing.add(key + "=true");
            for (String key :
                    List.of(
                            "jxsc.wechat.transfer.scene-id",
                            "jxsc.wechat.transfer.approval-reference",
                            "jxsc.wechat.transfer.scene-reports",
                            "jxsc.wechat.pay.digital-approval-reference",
                            "jxsc.wechat.pay.encryption-key-id"))
                if (value(e, key).isBlank()) missing.add(key);
            if (!value(e, "jxsc.wechat.pay.digital-path").equals("JSAPI_VERIFIED"))
                missing.add("jxsc.wechat.pay.digital-path");
            if (value(e, "jxsc.wechat.transfer.remark").isBlank())
                missing.add("jxsc.wechat.transfer.remark");
            try {
                if (e.getProperty("jxsc.wechat.transfer.max-fen", Long.class, 100000L) <= 0)
                    missing.add("jxsc.wechat.transfer.max-fen");
            } catch (RuntimeException x) {
                missing.add("jxsc.wechat.transfer.max-fen");
            }
            try {
                var reports =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(value(e, "jxsc.wechat.transfer.scene-reports"));
                if (reports == null || !reports.isArray() || reports.isEmpty())
                    throw new IllegalArgumentException();
                for (var r : reports) {
                    String type = r.path("info_type").asText(),
                            content = r.path("info_content").asText();
                    if (type.isBlank()
                            || type.length() > 15
                            || content.isBlank()
                            || content.length() > 32) throw new IllegalArgumentException();
                }
            } catch (Exception x) {
                missing.add("jxsc.wechat.transfer.scene-reports");
            }
        }
        return List.copyOf(missing);
    }

    private static String value(Environment e, String key) {
        return e.getProperty(key, "");
    }
}
