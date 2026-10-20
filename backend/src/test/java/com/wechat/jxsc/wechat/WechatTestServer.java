package com.wechat.jxsc.wechat;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.wechat.jxsc.payment.wechat.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.crypto.*;
import javax.crypto.spec.*;

public class WechatTestServer implements AutoCloseable {
    public static final String KEY = "01234567890123456789012345678901";
    public final ObjectMapper json = new ObjectMapper();
    final KeyPair merchant, platform;
    final HttpServer server;

    public record Request(String method, String path, JsonNode body) {}

    public record Response(int status, byte[] body, boolean signed) {}

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public volatile Function<Request, Response> responder =
            r -> jsonResponse(200, Map.of("prepay_id", "private-prepay"));
    public volatile Throwable failure;
    private volatile Clock signingClock = Clock.systemUTC();

    public WechatTestServer() {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            merchant = gen.generateKeyPair();
            platform = gen.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    e -> {
                        try {
                            byte[] data = e.getRequestBody().readAllBytes();
                            String auth = e.getRequestHeaders().getFirst("Authorization");
                            var parts = new HashMap<String, String>();
                            var matches =
                                    java.util.regex.Pattern.compile("(\\w+)=\"([^\"]+)\"")
                                            .matcher(auth);
                            while (matches.find()) parts.put(matches.group(1), matches.group(2));
                            var verifier = Signature.getInstance("SHA256withRSA");
                            verifier.initVerify(merchant.getPublic());
                            verifier.update(
                                    (e.getRequestMethod()
                                                    + "\n"
                                                    + e.getRequestURI()
                                                    + "\n"
                                                    + parts.get("timestamp")
                                                    + "\n"
                                                    + parts.get("nonce_str")
                                                    + "\n"
                                                    + new String(data, StandardCharsets.UTF_8)
                                                    + "\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            if (!verifier.verify(
                                    Base64.getDecoder().decode(parts.get("signature"))))
                                throw new AssertionError("Bad merchant signature");
                            var request =
                                    new Request(
                                            e.getRequestMethod(),
                                            e.getRequestURI().toString(),
                                            data.length == 0
                                                    ? json.createObjectNode()
                                                    : json.readTree(data));
                            requests.add(request);
                            var response = responder.apply(request);
                            if (response.signed())
                                headers(response.body())
                                        .forEach((k, v) -> e.getResponseHeaders().add(k, v));
                            e.sendResponseHeaders(
                                    response.status(),
                                    response.status() == 204 ? -1 : response.body().length);
                            if (response.status() != 204)
                                e.getResponseBody().write(response.body());
                        } catch (Throwable t) {
                            failure = t;
                        } finally {
                            e.close();
                        }
                    });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public WechatHttp transport(Clock clock) {
        return transport(clock, null);
    }

    public WechatHttp transport(Clock clock, String activeResponseKey) {
        signingClock = clock;
        return new WechatHttp(
                origin(),
                new WechatCrypto(
                        "1900001",
                        "CERT1",
                        merchant.getPrivate(),
                        Map.of("PUB1", platform.getPublic()),
                        KEY.getBytes(StandardCharsets.UTF_8),
                        clock),
                json,
                e -> {},
                activeResponseKey);
    }

    public Response jsonResponse(int status, Object value) {
        try {
            return new Response(status, json.writeValueAsBytes(value), true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, String> headers(byte[] body) throws Exception {
        String time = "" + signingClock.instant().getEpochSecond(), nonce = "callback-nonce";
        var s = Signature.getInstance("SHA256withRSA");
        s.initSign(platform.getPrivate());
        s.update((time + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
        s.update(body);
        s.update((byte) '\n');
        return Map.of(
                "Wechatpay-Timestamp",
                time,
                "Wechatpay-Nonce",
                nonce,
                "Wechatpay-Serial",
                "PUB1",
                "Wechatpay-Signature",
                Base64.getEncoder().encodeToString(s.sign()));
    }

    public byte[] callback(String event, String type, Object resource) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        String text =
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce).substring(0, 12);
        var c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, text.getBytes(StandardCharsets.UTF_8)));
        c.updateAAD("resource".getBytes(StandardCharsets.UTF_8));
        return json.writeValueAsBytes(
                Map.of(
                        "id",
                        event,
                        "event_type",
                        type,
                        "resource_type",
                        "encrypt-resource",
                        "resource",
                        Map.of(
                                "algorithm",
                                "AEAD_AES_256_GCM",
                                "nonce",
                                text,
                                "associated_data",
                                "resource",
                                "ciphertext",
                                Base64.getEncoder()
                                        .encodeToString(
                                                c.doFinal(json.writeValueAsBytes(resource))))));
    }

    public void close() {
        server.stop(0);
    }
}
