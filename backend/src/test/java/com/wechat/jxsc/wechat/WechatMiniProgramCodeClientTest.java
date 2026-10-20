package com.wechat.jxsc.wechat;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wechat.jxsc.distribution.WechatMiniProgramCodeClient;

import org.junit.jupiter.api.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class WechatMiniProgramCodeClientTest {
    HttpServer server;
    final ObjectMapper json = new ObjectMapper();
    final AtomicInteger tokens = new AtomicInteger(), images = new AtomicInteger();
    final byte[] png =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");
    volatile boolean rejected, invalidToken;
    volatile String path;
    volatile byte[] override;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                x -> {
                    byte[] response;
                    if (x.getRequestURI().getPath().equals("/cgi-bin/stable_token")) {
                        tokens.incrementAndGet();
                        var request = json.readTree(x.getRequestBody());
                        if (!request.path("appid").asText().equals("wx-test")
                                || !request.path("secret").asText().equals("private-secret")) {
                            x.sendResponseHeaders(400, -1);
                            x.close();
                            return;
                        }
                        response =
                                json.writeValueAsBytes(
                                        Map.of(
                                                "access_token",
                                                "private-token-" + tokens.get(),
                                                "expires_in",
                                                7200));
                    } else {
                        images.incrementAndGet();
                        path = json.readTree(x.getRequestBody()).path("path").asText();
                        response =
                                invalidToken && images.get() == 1
                                        ? json.writeValueAsBytes(
                                                Map.of("errcode", 40001, "errmsg", "secret text"))
                                        : rejected
                                                ? json.writeValueAsBytes(
                                                        Map.of(
                                                                "errcode",
                                                                45009,
                                                                "errmsg",
                                                                "secret text"))
                                                : override != null ? override : png;
                    }
                    x.sendResponseHeaders(200, response.length);
                    x.getResponseBody().write(response);
                    x.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    WechatMiniProgramCodeClient client(Clock clock) {
        return new WechatMiniProgramCodeClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "wx-test",
                "private-secret",
                json,
                clock);
    }

    @Test
    void generatesFullPathImageAndCachesAccessToken() throws Exception {
        var client = client(Clock.systemUTC());
        assertThat(
                        javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(
                                        client.generate(
                                                "pages/product/detail?id=42&scene=opaque"))))
                .isNotNull();
        assertThat(path).isEqualTo("pages/product/detail?id=42&scene=opaque");
        assertThat(
                        javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(
                                        client.generate("pages/product/detail?id=43&scene=other"))))
                .isNotNull();
        assertThat(tokens.get()).isEqualTo(1);
    }

    @Test
    void refreshesInvalidTokenOnceAndRedactsProviderErrors() throws Exception {
        invalidToken = true;
        var client = client(Clock.systemUTC());
        assertThat(
                        javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(
                                        client.generate(
                                                "pages/product/detail?id=42&scene=opaque"))))
                .isNotNull();
        assertThat(tokens.get()).isEqualTo(2);
        rejected = true;
        assertThatThrownBy(() -> client.generate("pages/product/detail?id=42&scene=opaque"))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class)
                .hasMessageNotContaining("secret text")
                .hasMessageNotContaining("private-token");
    }

    @Test
    void rejectsNonImagesAndOversizedResponses() {
        var client = client(Clock.systemUTC());
        override =
                "<html>invalid response</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.generate("pages/product/detail?id=42&scene=opaque"))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        override = new byte[1048577];
        assertThatThrownBy(() -> client.generate("pages/product/detail?id=42&scene=opaque"))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    @Test
    void expiresAccessTokenBeforeProviderDeadline() {
        var base = Instant.now();
        var now = new java.util.concurrent.atomic.AtomicReference<>(base);
        var clock =
                new Clock() {
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    public Instant instant() {
                        return now.get();
                    }
                };
        var client = client(clock);
        client.generate("pages/product/detail?id=42&scene=opaque");
        now.set(base.plusSeconds(7140));
        client.generate("pages/product/detail?id=42&scene=opaque");
        assertThat(tokens.get()).isEqualTo(2);
    }
}
