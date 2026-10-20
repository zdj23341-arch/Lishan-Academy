package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.storage.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

class ProductionScannerContractTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool;

    @Test
    void spoolUsesConfiguredDiskDirectoryAndCleansUpOnInputFailure() throws Exception {
        var scanner =
                new ProductionFormatScanner(
                        new MockEnvironment()
                                .withProperty("jxsc.scanner.spool-directory", spool.toString()));
        for (int attempt = 0; attempt < 3; attempt++) {
            var input =
                    new InputStream() {
                        public int read() throws IOException {
                            try (var files = java.nio.file.Files.list(spool)) {
                                assertThat(files.count()).isEqualTo(1);
                            }
                            throw new IOException("Synthetic source failure");
                        }
                    };
            assertThatThrownBy(() -> scanner.scan("PNG", input)).isInstanceOf(IOException.class);
            try (var files = java.nio.file.Files.list(spool)) {
                assertThat(files.count()).isZero();
            }
        }
    }

    @Test
    void streamsClamAvProtocolAndFailsClosed() throws Exception {
        byte[] png;
        try (var b = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, 2), "png", b);
            png = b.toByteArray();
        }
        for (String reply :
                new String[] {
                    "stream: OK\0",
                    "stream: Eicar-Test-Signature FOUND\0",
                    "stream: size limit exceeded ERROR\0"
                }) {
            try (var server = new ServerSocket(0);
                    var pool = Executors.newSingleThreadExecutor();
                    var ctx = new AnnotationConfigApplicationContext()) {
                server.setSoTimeout(3000);
                var observed =
                        pool.submit(
                                () -> {
                                    try (var socket = server.accept()) {
                                        socket.setSoTimeout(3000);
                                        var in = new DataInputStream(socket.getInputStream());
                                        assertThat(in.readNBytes(10))
                                                .isEqualTo(
                                                        "zINSTREAM\0"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .US_ASCII));
                                        var bytes = new ByteArrayOutputStream();
                                        int n;
                                        while ((n = in.readInt()) != 0) {
                                            assertThat(n).isBetween(1, 65536);
                                            bytes.write(in.readNBytes(n));
                                        }
                                        socket.getOutputStream()
                                                .write(
                                                        reply.getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .US_ASCII));
                                        return bytes.toByteArray();
                                    }
                                });
                var env =
                        new MockEnvironment()
                                .withProperty("jxsc.providers.storage", "REAL")
                                .withProperty("jxsc.scanner.host", "127.0.0.1")
                                .withProperty("jxsc.scanner.port", "" + server.getLocalPort());
                env.setActiveProfiles("test");
                ctx.setEnvironment(env);
                ctx.register(StorageConfiguration.class);
                ctx.refresh();
                var scanner = ctx.getBean(FormatScanner.class);
                if (reply.contains("ERROR"))
                    assertThatThrownBy(() -> scanner.accepts("PNG", new ByteArrayInputStream(png)))
                            .isInstanceOf(IOException.class);
                else
                    assertThat(scanner.accepts("PNG", new ByteArrayInputStream(png)))
                            .isEqualTo(reply.contains("OK"));
                assertThat(observed.get(3, TimeUnit.SECONDS)).isEqualTo(png);
                assertThat(scanner.status()).isEqualTo("CLEAN_AND_FORMAT_VALIDATED");
            }
        }
    }
}
