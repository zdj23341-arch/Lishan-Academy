package com.wechat.jxsc.common.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.env.Environment;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;

import javax.xml.stream.*;

/** ClamAV INSTREAM plus bounded format validation. Dependency errors are retriable, never clean. */
public final class ProductionFormatScanner implements FormatScanner {
    private final String host, ffprobe, ffmpeg;
    private final int port;
    private final Path spool;
    private static final Semaphore CAPACITY = new Semaphore(2);
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "scanner-deadline");
                        t.setDaemon(true);
                        return t;
                    });

    public ProductionFormatScanner(Environment e) {
        host = e.getProperty("jxsc.scanner.host", "");
        port = e.getProperty("jxsc.scanner.port", Integer.class, 3310);
        ffprobe = e.getProperty("jxsc.media.ffprobe", "");
        ffmpeg = e.getProperty("jxsc.media.ffmpeg", "");
        spool =
                Path.of(
                                e.getProperty(
                                        "jxsc.scanner.spool-directory",
                                        Path.of(
                                                        System.getProperty("java.io.tmpdir"),
                                                        "jxsc-scanner")
                                                .toString()))
                        .toAbsolutePath();
    }

    public String status() {
        return "CLEAN_AND_FORMAT_VALIDATED";
    }

    public boolean accepts(String extension, InputStream input) throws IOException {
        return scan(extension, input).accepted();
    }

    @Override
    public ScanResult scan(String extension, InputStream input) throws IOException {
        if (!CAPACITY.tryAcquire()) throw new IOException("Scanner capacity exhausted");
        Path file = null;
        try {
            long max =
                    extension.equals("MP4")
                            ? 2147483648L
                            : Set.of("PNG", "JPG", "JPEG", "WEBP").contains(extension)
                                    ? 10485760L
                                    : 52428800L;
            Files.createDirectories(spool);
            if (Files.isSymbolicLink(spool))
                throw new IOException("Scanner spool cannot be a symlink");
            if (Files.getFileStore(spool).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(
                        spool,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            if (Files.getFileStore(spool).getUsableSpace() < max + 67108864L)
                throw new IOException("Scanner spool capacity unavailable");
            file = Files.createTempFile(spool, "jxsc-verify-", ".upload");
            try (var out = Files.newOutputStream(file)) {
                byte[] chunk = new byte[65536];
                long total = 0;
                int n;
                while ((n = input.read(chunk)) != -1) {
                    total += n;
                    if (total > max) return new ScanResult(false, "FORMAT_REJECTED");
                    out.write(chunk, 0, n);
                }
                if (total == 0) return new ScanResult(false, "FORMAT_REJECTED");
            }
            if (!antivirus(file)) return new ScanResult(false, "MALWARE_DETECTED");
            if (extension.equals("DOCX") && !safeXml(file))
                return new ScanResult(false, "FORMAT_REJECTED");
            if (extension.equals("MP4") || extension.equals("WEBP")) {
                try (var in = Files.newInputStream(file)) {
                    if (!new DevelopmentFormatScanner().accepts(extension, in))
                        return new ScanResult(false, "FORMAT_REJECTED");
                }
                boolean valid = media(file, extension);
                return new ScanResult(valid, valid ? status() : "FORMAT_REJECTED");
            }
            try (var in = Files.newInputStream(file)) {
                boolean valid = new DevelopmentFormatScanner().accepts(extension, in);
                return new ScanResult(valid, valid ? status() : "FORMAT_REJECTED");
            }
        } finally {
            try {
                if (file != null) Files.deleteIfExists(file);
            } finally {
                CAPACITY.release();
            }
        }
    }

    private boolean antivirus(Path file) throws IOException {
        if (host.isBlank()) throw new IOException("jxsc.scanner.host is required");
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30000);
            var timeout =
                    TIMER.schedule(
                            () -> {
                                try {
                                    socket.close();
                                } catch (IOException ignored) {
                                }
                            },
                            300,
                            TimeUnit.SECONDS);
            try {
                var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                try (var in = Files.newInputStream(file)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) != -1) {
                        out.writeInt(n);
                        out.write(b, 0, n);
                    }
                }
                out.writeInt(0);
                out.flush();
                var reply = new ByteArrayOutputStream();
                int b;
                while ((b = socket.getInputStream().read()) > 0) {
                    if (reply.size() >= 4096) throw new IOException("ClamAV response too large");
                    reply.write(b);
                }
                if (b != 0) throw new IOException("Truncated ClamAV response");
                String text = reply.toString(StandardCharsets.US_ASCII);
                if (text.equals("stream: OK")) return true;
                if (text.startsWith("stream: ") && text.endsWith(" FOUND")) return false;
                throw new IOException("ClamAV scan unavailable");
            } finally {
                timeout.cancel(false);
            }
        }
    }

    private boolean safeXml(Path file) throws IOException {
        try (var zip = new ZipInputStream(Files.newInputStream(file))) {
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setXMLResolver(
                    (a, b, c, d) -> {
                        throw new XMLStreamException("External entity forbidden");
                    });
            int entries = 0;
            long expanded = 0;
            java.util.zip.ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > 1000) return false;
                byte[] bytes = zip.readNBytes(10485761);
                expanded += bytes.length;
                if (bytes.length > 10485760 || expanded > 52428800) return false;
                if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels")) {
                    var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
                    int depth = 0, nodes = 0;
                    try {
                        while (reader.hasNext()) {
                            int event = reader.next();
                            if (++nodes > 100000) return false;
                            if (event == XMLStreamConstants.DTD
                                    || event == XMLStreamConstants.ENTITY_REFERENCE) return false;
                            if (event == XMLStreamConstants.START_ELEMENT
                                    && (++depth > 64 || reader.getAttributeCount() > 100))
                                return false;
                            if (event == XMLStreamConstants.END_ELEMENT) depth--;
                        }
                    } finally {
                        reader.close();
                    }
                }
            }
            return true;
        } catch (XMLStreamException e) {
            return false;
        }
    }

    private boolean media(Path file, String extension) throws IOException {
        if (ffprobe.isBlank() || ffmpeg.isBlank())
            throw new IOException("jxsc.media.ffprobe and jxsc.media.ffmpeg are required");
        var probe =
                run(
                        List.of(
                                ffprobe,
                                "-v",
                                "error",
                                "-max_alloc",
                                "67108864",
                                "-protocol_whitelist",
                                "file,pipe",
                                "-show_entries",
                                "stream=codec_name,codec_type,width,height:format=duration,format_name",
                                "-of",
                                "json",
                                file.toString()),
                        30);
        if (probe.code() != 0) return false;
        try {
            var root = new ObjectMapper().readTree(probe.output());
            var streams = root.path("streams");
            if (!streams.isArray() || streams.size() == 0 || streams.size() > 2) return false;
            int videos = 0;
            for (var stream : streams) {
                String type = stream.path("codec_type").asText(),
                        codec = stream.path("codec_name").asText();
                if (type.equals("video")) {
                    videos++;
                    int width = stream.path("width").asInt(),
                            height = stream.path("height").asInt();
                    if (width <= 0
                            || height <= 0
                            || width > 8192
                            || height > 8192
                            || (long) width * height > 25000000) return false;
                    if (!codec.equals(extension.equals("MP4") ? "h264" : "webp")) return false;
                } else if (!extension.equals("MP4")
                        || !type.equals("audio")
                        || !codec.equals("aac")) return false;
            }
            if (videos != 1) return false;
            if (extension.equals("MP4")) {
                double duration = root.path("format").path("duration").asDouble(-1);
                if (!Double.isFinite(duration) || duration <= 0 || duration > 14400) return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return run(
                                List.of(
                                        ffmpeg,
                                        "-nostdin",
                                        "-v",
                                        "error",
                                        "-xerror",
                                        "-max_alloc",
                                        "67108864",
                                        "-threads",
                                        "1",
                                        "-protocol_whitelist",
                                        "file,pipe",
                                        "-i",
                                        file.toString(),
                                        "-map",
                                        "0:v",
                                        "-map",
                                        "0:a?",
                                        "-f",
                                        "null",
                                        "-"),
                                300)
                        .code()
                == 0;
    }

    private record ProcessResult(int code, byte[] output) {}

    private ProcessResult run(List<String> args, int seconds) throws IOException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var timeout = TIMER.schedule(process::destroyForcibly, seconds, TimeUnit.SECONDS);
        try (var in = process.getInputStream()) {
            byte[] result = in.readNBytes(65537);
            if (result.length > 65536) {
                process.destroyForcibly();
                throw new IOException("Media verifier output limit");
            }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Media verifier timed out");
            }
            if (timeout.isDone()) throw new IOException("Media verifier timed out");
            return new ProcessResult(process.exitValue(), result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Media verifier interrupted");
        } finally {
            timeout.cancel(false);
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
