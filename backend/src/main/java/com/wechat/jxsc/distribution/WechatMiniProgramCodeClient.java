package com.wechat.jxsc.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.payment.wechat.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import javax.imageio.ImageIO;

@Component
public class WechatMiniProgramCodeClient {
    private final URI origin;
    private final String appId, secret;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private String token;
    private Instant tokenExpires = Instant.MIN;
    private boolean forceRefresh;

    @Autowired
    public WechatMiniProgramCodeClient(WechatSettings settings, ObjectMapper json, Clock clock) {
        this(
                URI.create("https://api.weixin.qq.com"),
                settings.appId(),
                settings.get("login.app-secret"),
                json,
                clock);
    }

    public WechatMiniProgramCodeClient(
            URI origin, String appId, String secret, ObjectMapper json, Clock clock) {
        this.origin = origin;
        this.appId = appId;
        this.secret = secret;
        this.json = json;
        this.clock = clock;
    }

    public byte[] generate(String path) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("WeChat I/O must run outside database transactions");
        if (appId.isBlank() || secret.isBlank())
            throw WechatProtocol.unavailable("WECHAT_SHARE_NOT_CONFIGURED");
        if (!path.startsWith("pages/product/detail?id=") || path.length() > 1024)
            throw new IllegalArgumentException("Invalid share path");
        for (int attempt = 0; attempt < 2; attempt++) {
            String credential = accessToken();
            byte[] body =
                    post(
                            "/wxa/getwxacode?access_token=" + WechatProtocol.path(credential),
                            Map.of("path", path, "width", 430, "env_version", "release"),
                            1048576);
            // Errors are JSON even when HTTP status is 200. Never expose body or credentials.
            if (body.length > 0 && body[0] == '{') {
                try {
                    int error = json.readTree(body).path("errcode").asInt();
                    if (attempt == 0 && (error == 40001 || error == 40014 || error == 42001)) {
                        invalidate(credential);
                        continue;
                    }
                } catch (IOException ignored) {
                }
                throw WechatProtocol.failure("WECHAT_SHARE_REJECTED");
            }
            return png(body);
        }
        throw WechatProtocol.failure("WECHAT_SHARE_REJECTED");
    }

    private synchronized String accessToken() {
        if (token != null && tokenExpires.isAfter(clock.instant())) return token;
        byte[] data =
                post(
                        "/cgi-bin/stable_token",
                        Map.of(
                                "grant_type",
                                "client_credential",
                                "appid",
                                appId,
                                "secret",
                                secret,
                                "force_refresh",
                                forceRefresh),
                        65536);
        try {
            var response = json.readTree(data);
            String value = WechatProtocol.text(response, "access_token");
            var seconds = response.path("expires_in");
            if (!seconds.isIntegralNumber() || seconds.asLong() < 120 || seconds.asLong() > 86400)
                throw WechatProtocol.failure("WECHAT_SHARE_INVALID_TOKEN");
            token = value;
            forceRefresh = false;
            tokenExpires = clock.instant().plusSeconds(seconds.asLong() - 60);
            return value;
        } catch (IOException e) {
            throw WechatProtocol.failure("WECHAT_SHARE_INVALID_TOKEN");
        }
    }

    private synchronized void invalidate(String used) {
        if (used.equals(token)) {
            token = null;
            tokenExpires = Instant.MIN;
            forceRefresh = true;
        }
    }

    private byte[] post(String path, Object payload, int maximum) {
        try {
            var request =
                    HttpRequest.newBuilder(origin.resolve(path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            json.writeValueAsBytes(payload)))
                            .build();
            var result = WechatResponseBody.send(http, request, maximum);
            if (result.statusCode() != 200) throw WechatProtocol.failure("WECHAT_SHARE_HTTP_ERROR");
            return result.body();
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw WechatProtocol.failure("WECHAT_SHARE_INTERRUPTED");
        } catch (Exception e) {
            throw WechatProtocol.failure("WECHAT_SHARE_UNAVAILABLE");
        }
    }

    private byte[] png(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Not an image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"))
                        || reader.getWidth(0) < 1
                        || reader.getHeight(0) < 1
                        || reader.getWidth(0) > 1280
                        || reader.getHeight(0) > 1280)
                    throw new IOException("Invalid image dimensions/format");
                var image = reader.read(0);
                var output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                if (output.size() > 1048576) throw new IOException("Image too large");
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw WechatProtocol.failure("WECHAT_SHARE_INVALID_IMAGE");
        }
    }
}
