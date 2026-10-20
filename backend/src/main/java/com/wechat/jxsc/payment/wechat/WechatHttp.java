package com.wechat.jxsc.payment.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import com.fasterxml.jackson.databind.*;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** No redirects, automatic financial retries, raw logging, or transport under a DB transaction. */
public final class WechatHttp {
    private final URI origin;
    private final WechatCrypto crypto;
    private final ObjectMapper json;
    private final String responseSerial;

    public record Exchange(
            String method,
            String path,
            String request,
            String response,
            Map<String, String> headers) {}

    private final java.util.function.Consumer<Exchange> evidence;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public WechatHttp(URI origin, WechatCrypto crypto, ObjectMapper json) {
        this(origin, crypto, json, e -> {});
    }

    public WechatHttp(
            URI origin,
            WechatCrypto crypto,
            ObjectMapper json,
            java.util.function.Consumer<Exchange> evidence) {
        this(origin, crypto, json, evidence, null);
    }

    public WechatHttp(
            URI origin,
            WechatCrypto crypto,
            ObjectMapper json,
            java.util.function.Consumer<Exchange> evidence,
            String responseSerial) {
        this.origin = origin;
        this.crypto = crypto;
        this.json = json;
        this.evidence = evidence;
        this.responseSerial = responseSerial;
        if (responseSerial != null && (crypto == null || !crypto.trusts(responseSerial)))
            throw new IllegalArgumentException("Active WeChat response key must be trusted");
    }

    public boolean ready() {
        return crypto != null;
    }

    public WechatCrypto crypto() {
        if (!ready()) throw unavailable("WECHAT_NOT_CONFIGURED");
        return crypto;
    }

    public void requireEncryptionKey(String serial) {
        if (!crypto().trusts(serial) || (responseSerial != null && !responseSerial.equals(serial)))
            throw unavailable("WECHAT_RECIPIENT_ENCRYPTION_UNAVAILABLE");
    }

    public record Reply(int status, JsonNode body, String signedEvidence) {}

    public Reply signedJson(String method, String path, Object payload) {
        try {
            String body = payload == null ? "" : json.writeValueAsString(payload);
            var response = execute(method, origin.resolve(path), body, 1024 * 1024, Map.of());
            crypto().verify(response.body(), response.headers());
            var exchange =
                    new Exchange(
                            method,
                            path,
                            body,
                            new String(response.body(), java.nio.charset.StandardCharsets.UTF_8),
                            response.headers());
            evidence.accept(exchange);
            return new Reply(
                    response.status(),
                    response.body().length == 0
                            ? json.createObjectNode()
                            : json.readTree(response.body()),
                    json.writeValueAsString(exchange));
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw failure("WECHAT_INVALID_RESPONSE");
        }
    }

    public JsonNode json(String method, String path, Object payload) {
        var response = signedJson(method, path, payload);
        if (response.status() < 200 || response.status() >= 300)
            throw failure("WECHAT_CHANNEL_UNRESOLVED");
        return response.body();
    }

    public JsonNode json(String method, String path, Object payload, String encryptionSerial) {
        requireEncryptionKey(encryptionSerial);
        try {
            var response =
                    execute(
                            method,
                            origin.resolve(path),
                            json.writeValueAsString(payload),
                            1024 * 1024,
                            Map.of("Wechatpay-Serial", encryptionSerial));
            crypto().verify(response.body(), response.headers());
            evidence.accept(
                    new Exchange(
                            method,
                            path,
                            json.writeValueAsString(payload),
                            new String(response.body(), java.nio.charset.StandardCharsets.UTF_8),
                            response.headers()));
            if (response.status() < 200 || response.status() >= 300)
                throw failure("WECHAT_CHANNEL_UNRESOLVED");
            return json.readTree(response.body());
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw failure("WECHAT_INVALID_RESPONSE");
        }
    }

    public byte[] download(String url, int maximum) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw failure("WECHAT_STATEMENT_URL_REJECTED");
        }
        if (!Objects.equals(origin.getScheme(), uri.getScheme())
                || !Objects.equals(origin.getRawAuthority(), uri.getRawAuthority())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !"/v3/billdownload/file".equals(uri.getPath()))
            throw failure("WECHAT_STATEMENT_URL_REJECTED");
        var r = execute("GET", uri, "", maximum, Map.of());
        if (r.status() != 200) throw failure("WECHAT_STATEMENT_UNAVAILABLE");
        // Official download deliberately has no signature. Caller MUST verify signed apply hash.
        return r.body();
    }

    private record Response(int status, byte[] body, Map<String, String> headers) {}

    private Response execute(
            String method, URI uri, String body, int max, Map<String, String> extra) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("WeChat transport cannot run in a transaction");
        var key = crypto();
        try {
            String path =
                    uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            var builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .header("Authorization", key.authorization(method, path, body));
            String selectedSerial = extra.getOrDefault("Wechatpay-Serial", responseSerial);
            if (selectedSerial != null) builder.header("Wechatpay-Serial", selectedSerial);
            var r =
                    WechatResponseBody.send(
                            client,
                            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            max);
            byte[] bytes = r.body();
            Map<String, String> headers = new HashMap<>();
            r.headers()
                    .map()
                    .forEach(
                            (k, v) -> {
                                if (v.size() != 1) throw failure("WECHAT_INVALID_SIGNATURE");
                                headers.put(k, v.getFirst());
                            });
            return new Response(r.statusCode(), bytes, headers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("WECHAT_TRANSPORT_UNKNOWN");
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw failure("WECHAT_TRANSPORT_UNKNOWN");
        }
    }
}
