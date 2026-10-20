package com.wechat.jxsc.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

class Kuaidi100ContractTest {
    @Test
    void signsExactFormAndChecksAssociation() throws Exception {
        var request = new AtomicReference<String>();
        var response =
                new AtomicReference<String>(
                        "{\"status\":\"200\",\"com\":\"yunda\",\"nu\":\"123456789\",\"state\":\"3\",\"data\":[{\"ftime\":\"2026-10-07"
                            + " 12:00:00\",\"context\":\"Delivered\"}]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/query",
                x -> {
                    request.set(
                            new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        server.start();
        try {
            var env =
                    new MockEnvironment()
                            .withProperty("jxsc.providers.logistics", "REAL")
                            .withProperty("jxsc.logistics.customer", "customer")
                            .withProperty("jxsc.logistics.key", "key")
                            .withProperty(
                                    "jxsc.logistics.endpoint",
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/query");
            env.setActiveProfiles("test");
            var provider = new LogisticsConfiguration().logisticsProvider(env, Clock.systemUTC());
            assertThat(provider.query("yunda", "123456789").status()).isEqualTo("SIGNED");
            assertThat(request.get()).contains("customer=customer", "sign=");
            var form =
                    java.util.Arrays.stream(request.get().split("&"))
                            .map(p -> p.split("=", 2))
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            p -> p[0],
                                            p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
            assertThat(form.get("sign"))
                    .isEqualTo(
                            java.util.HexFormat.of()
                                    .withUpperCase()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("MD5")
                                                    .digest(
                                                            (form.get("param") + "keycustomer")
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))));
            response.set(
                    "{\"status\":\"200\",\"com\":\"yunda\",\"nu\":\"987654321\",\"state\":\"3\",\"data\":[]}");
            assertThatThrownBy(() -> provider.query("yunda", "123456789"))
                    .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        } finally {
            server.stop(0);
        }
    }
}
