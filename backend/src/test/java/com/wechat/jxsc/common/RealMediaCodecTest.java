package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.storage.ProductionFormatScanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual FFmpeg/ffprobe processes; only the antivirus socket collaborator is a test boundary. */
@EnabledIfSystemProperty(named = "jxsc.test.ffmpeg", matches = ".+")
class RealMediaCodecTest {
    @TempDir Path temp;

    @Test
    void decodesH264ButRejectsUnsupportedCodecAndFakeContainer() throws Exception {
        String ffmpeg = System.getProperty("jxsc.test.ffmpeg"),
                ffprobe = System.getProperty("jxsc.test.ffprobe");
        try (var server = new ServerSocket(0);
                var pool = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(15000);
            var daemon =
                    pool.submit(
                            () -> {
                                for (int i = 0; i < 3; i++)
                                    try (var socket = server.accept()) {
                                        var in = new DataInputStream(socket.getInputStream());
                                        in.readNBytes(10);
                                        int size;
                                        while ((size = in.readInt()) != 0) in.skipNBytes(size);
                                        socket.getOutputStream()
                                                .write(
                                                        "stream: OK\0"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .US_ASCII));
                                    }
                                return true;
                            });
            var scanner =
                    new ProductionFormatScanner(
                            new MockEnvironment()
                                    .withProperty("jxsc.scanner.host", "127.0.0.1")
                                    .withProperty("jxsc.scanner.port", "" + server.getLocalPort())
                                    .withProperty("jxsc.media.ffmpeg", ffmpeg)
                                    .withProperty("jxsc.media.ffprobe", ffprobe));
            for (String codec : List.of("libx264", "mpeg4")) {
                Path path = temp.resolve(codec + ".mp4");
                var process =
                        new ProcessBuilder(
                                        ffmpeg,
                                        "-nostdin",
                                        "-v",
                                        "error",
                                        "-f",
                                        "lavfi",
                                        "-i",
                                        "color=c=black:s=16x16:r=1",
                                        "-t",
                                        "1",
                                        "-c:v",
                                        codec,
                                        "-pix_fmt",
                                        "yuv420p",
                                        "-movflags",
                                        "+faststart",
                                        path.toString())
                                .redirectErrorStream(true)
                                .start();
                String output = new String(process.getInputStream().readAllBytes());
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(output).isZero();
                try (var in = Files.newInputStream(path)) {
                    assertThat(scanner.accepts("MP4", in))
                            .as(codec)
                            .isEqualTo(codec.equals("libx264"));
                }
            }
            byte[] fake =
                    HexFormat.of()
                            .parseHex(
                                    "000000106674797069736f6d00000000000000106d6f6f7600000000000000000000000c6d64617400000000");
            assertThat(scanner.accepts("MP4", new ByteArrayInputStream(fake))).isFalse();
            assertThat(daemon.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
