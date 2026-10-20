package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.wechat.jxsc.common.storage.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

class CosStorageContractTest {
    @Test
    void generatedImageUsesBoundedSignedPutWithOverwriteProtection() throws Exception {
        var received = new AtomicReference<byte[]>();
        var overwrite = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                x -> {
                    received.set(x.getRequestBody().readAllBytes());
                    overwrite.set(x.getRequestHeaders().getFirst("x-cos-forbid-overwrite"));
                    x.sendResponseHeaders(200, -1);
                    x.close();
                });
        server.start();
        try {
            var env =
                    new MockEnvironment()
                            .withProperty(
                                    "jxsc.storage.cos.endpoint",
                                    "http://127.0.0.1:" + server.getAddress().getPort())
                            .withProperty("jxsc.storage.cos.secret-id", "test-id")
                            .withProperty("jxsc.storage.cos.secret-key", "test-key");
            env.setActiveProfiles("test");
            var provider = new CosStorageProvider(env);
            provider.writeOnce(
                    "generated", new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
            assertThat(received.get()).containsExactly(1, 2, 3);
            assertThat(overwrite.get()).isEqualTo("true");
            assertThatThrownBy(
                            () ->
                                    provider.writeOnce(
                                            "oversized",
                                            new java.io.ByteArrayInputStream(new byte[0]),
                                            1048577))
                    .isInstanceOf(java.io.IOException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pinsServerVersionAndNeverTreatsEtagAsDigest() throws Exception {
        var version = new AtomicReference<String>("v1");
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                x -> {
                    query.set(x.getRequestURI().getRawQuery());
                    x.getResponseHeaders()
                            .set(
                                    "x-cos-version-id",
                                    x.getRequestURI().getRawQuery().contains("versionId=v1")
                                            ? "v1"
                                            : version.get());
                    x.getResponseHeaders().set("Content-Length", "3");
                    x.getResponseHeaders().set("ETag", "multipart-not-sha256-2");
                    if (x.getRequestMethod().equals("HEAD")) x.sendResponseHeaders(200, -1);
                    else {
                        x.sendResponseHeaders(200, 3);
                        x.getResponseBody().write(new byte[] {1, 2, 3});
                    }
                    x.close();
                });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            var e =
                    new MockEnvironment()
                            .withProperty("jxsc.providers.storage", "REAL")
                            .withProperty(
                                    "jxsc.storage.cos.endpoint",
                                    "http://127.0.0.1:" + server.getAddress().getPort())
                            .withProperty("jxsc.storage.cos.secret-id", "test-id")
                            .withProperty("jxsc.storage.cos.secret-key", "test-key");
            e.setActiveProfiles("test");
            context.setEnvironment(e);
            context.register(StorageConfiguration.class);
            context.refresh();
            var provider = context.getBean(StorageProvider.class);
            var sealed = provider.seal("testkey");
            assertThat(sealed.version()).isEqualTo("v1");
            version.set("v2");
            try (var body = provider.open(sealed)) {
                assertThat(body.readAllBytes()).containsExactly(1, 2, 3);
            }
            assertThat(query.get()).contains("versionId=v1");
            String url = provider.sign(sealed, Instant.now().plusSeconds(60)).url();
            assertThat(url).contains("versionId=v1", "q-signature=", "q-url-param-list=versionid");
            version.set("null");
            assertThatThrownBy(() -> provider.seal("testkey"))
                    .isInstanceOf(java.io.IOException.class);
        } finally {
            server.stop(0);
        }
    }
}
