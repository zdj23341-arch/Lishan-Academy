package com.wechat.jxsc.common.storage;

import org.springframework.core.env.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** COS bucket versioning is mandatory. Every verified/read object uses its immutable versionId. */
public final class CosStorageProvider implements StorageProvider {
    private final URI endpoint;
    private final String secretId, secretKey, securityToken;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public CosStorageProvider(Environment e) {
        endpoint =
                URI.create(
                        e.getProperty(
                                "jxsc.storage.cos.endpoint",
                                "https://unconfigured.cos.ap-guangzhou.myqcloud.com"));
        secretId = e.getProperty("jxsc.storage.cos.secret-id", "");
        secretKey = e.getProperty("jxsc.storage.cos.secret-key", "");
        securityToken = e.getProperty("jxsc.storage.cos.security-token", "");
        boolean test =
                e.acceptsProfiles(Profiles.of("test")) && !e.acceptsProfiles(Profiles.of("prod"));
        if (endpoint.getRawQuery() != null
                || endpoint.getRawUserInfo() != null
                || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
                || !(endpoint.getScheme().equals("https")
                                && endpoint.getHost()
                                        .matches("[a-z0-9-]+\\.cos\\.[a-z0-9-]+\\.myqcloud\\.com")
                                && endpoint.getPort() == -1)
                        && !(test
                                && endpoint.getScheme().equals("http")
                                && endpoint.getHost().equals("127.0.0.1")))
            throw new IllegalStateException(
                    "jxsc.storage.cos.endpoint must be a trusted COS bucket endpoint");
    }

    public boolean directUpload() {
        return true;
    }

    public String uploadUrl(long id) {
        throw StorageConfiguration.unavailable();
    }

    @Override
    public String uploadUrl(long id, String key, String mime, long bytes, Instant expiry) {
        return signed(
                "PUT",
                key,
                Map.of(),
                Map.of("content-type", mime, "content-length", Long.toString(bytes)),
                expiry);
    }

    public void writeOnce(String key, InputStream source, long bytes) throws IOException {
        // Small server-generated PNGs only. User uploads continue to use signed direct PUT.
        if (bytes < 1 || bytes > 1048576) throw new IOException("Generated object size invalid");
        byte[] body = source.readNBytes((int) bytes + 1);
        if (body.length != bytes) throw new IOException("Generated object size mismatch");
        var headers = Map.of("content-type", "image/png", "x-cos-forbid-overwrite", "true");
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        signed(
                                                "PUT",
                                                key,
                                                Map.of(),
                                                headers,
                                                Instant.now().plusSeconds(60))))
                        .timeout(Duration.ofSeconds(20))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try {
            var response =
                    com.wechat.jxsc.payment.wechat.WechatResponseBody.send(
                            http, request.build(), 65536);
            if (response.statusCode() != 200)
                throw new IOException("Generated object write failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Generated object write interrupted");
        } catch (Exception e) {
            throw new IOException("Generated object write failed");
        }
    }

    public SealedObject seal(String key) throws IOException {
        var reply = exchange("HEAD", key, Map.of(), Map.of());
        try (var ignored = reply.body()) {
            if (reply.statusCode() != 200) throw new IOException("COS object unavailable");
            String version = reply.headers().firstValue("x-cos-version-id").orElse("");
            long bytes = reply.headers().firstValueAsLong("content-length").orElse(-1);
            if (version.isBlank()
                    || version.equals("null")
                    || version.length() > 128
                    || bytes <= 0
                    || bytes > 2147483648L)
                throw new IOException("COS versioning or length invalid");
            return new SealedObject(key, version, bytes);
        }
    }

    public InputStream open(SealedObject object) throws IOException {
        return open(object, 0, object.bytes() - 1);
    }

    @Override
    public InputStream open(SealedObject object, long start, long end) throws IOException {
        if (start < 0 || end < start || end >= object.bytes())
            throw new IOException("Invalid object range");
        boolean range = start != 0 || end != object.bytes() - 1;
        var reply =
                exchange(
                        "GET",
                        object.key(),
                        Map.of("versionId", object.version()),
                        range ? Map.of("range", "bytes=" + start + "-" + end) : Map.of());
        if (reply.statusCode() != (range ? 206 : 200)
                || !reply.headers()
                        .firstValue("x-cos-version-id")
                        .orElse("")
                        .equals(object.version())
                || reply.headers().firstValueAsLong("content-length").orElse(-1) != end - start + 1
                || range
                        && !reply.headers()
                                .firstValue("content-range")
                                .orElse("")
                                .equals("bytes " + start + "-" + end + "/" + object.bytes())) {
            reply.body().close();
            throw new IOException("COS version or range mismatch");
        }
        return BoundedStreams.deadline(reply.body(), end - start + 1, Duration.ofMinutes(5));
    }

    public SignedUrl sign(SealedObject object, Instant expiry) {
        return new SignedUrl(
                signed(
                        "GET",
                        object.key(),
                        Map.of("versionId", object.version()),
                        Map.of(),
                        expiry),
                expiry);
    }

    private HttpResponse<InputStream> exchange(
            String method, String key, Map<String, String> query, Map<String, String> headers)
            throws IOException {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        signed(
                                                method,
                                                key,
                                                query,
                                                headers,
                                                Instant.now().plusSeconds(60))))
                        .timeout(Duration.ofSeconds(20))
                        .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("COS request interrupted");
        }
    }

    private String signed(
            String method,
            String key,
            Map<String, String> query,
            Map<String, String> extra,
            Instant expiry) {
        if (secretId.isBlank() || secretKey.isBlank() || !key.matches("[a-zA-Z0-9_-]{1,64}"))
            throw StorageConfiguration.unavailable();
        long now = Instant.now().getEpochSecond();
        if (expiry.getEpochSecond() <= now || expiry.getEpochSecond() > now + 3600)
            throw StorageConfiguration.unavailable();
        var q = new TreeMap<String, String>(query);
        if (!securityToken.isBlank()) q.put("x-cos-security-token", securityToken);
        var h = new TreeMap<String, String>(extra);
        h.put("host", endpoint.getAuthority());
        String time = (now - 5) + ";" + expiry.getEpochSecond();
        String canonical =
                method.toLowerCase(Locale.ROOT)
                        + "\n/"
                        + key
                        + "\n"
                        + canonical(q)
                        + "\n"
                        + canonical(h)
                        + "\n";
        String signKey = hmac(secretKey, time);
        String signature = hmac(signKey, "sha1\n" + time + "\n" + sha1(canonical) + "\n");
        String auth =
                "q-sign-algorithm=sha1&q-ak="
                        + encode(secretId)
                        + "&q-sign-time="
                        + encode(time)
                        + "&q-key-time="
                        + encode(time)
                        + "&q-header-list="
                        + encode(names(h))
                        + "&q-url-param-list="
                        + encode(names(q))
                        + "&q-signature="
                        + signature;
        String params =
                q.entrySet().stream()
                        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                        .collect(java.util.stream.Collectors.joining("&"));
        return endpoint.toString().replaceAll("/$", "")
                + "/"
                + key
                + "?"
                + (params.isEmpty() ? "" : params + "&")
                + auth;
    }

    private static String canonical(Map<String, String> values) {
        var normalized = new TreeMap<String, String>();
        values.forEach((k, v) -> normalized.put(encode(k).toLowerCase(Locale.ROOT), encode(v)));
        return normalized.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String names(Map<String, String> values) {
        return values.keySet().stream()
                .map(k -> encode(k).toLowerCase(Locale.ROOT))
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-1")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmac(String key, String s) {
        try {
            var m = Mac.getInstance("HmacSHA1");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().formatHex(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
