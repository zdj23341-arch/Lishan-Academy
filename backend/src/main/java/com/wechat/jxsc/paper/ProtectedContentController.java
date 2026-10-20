package com.wechat.jxsc.paper;

import com.wechat.jxsc.auth.TokenCodec;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.storage.AssetService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

@RestController
@RequestMapping("/api/v1")
public class ProtectedContentController {
    private final ContentAuthorization authorization;
    private final AssetService assets;
    private final Clock clock;

    public ProtectedContentController(
            ContentAuthorization authorization, AssetService assets, Clock clock) {
        this.authorization = authorization;
        this.assets = assets;
        this.clock = clock;
    }

    @PostMapping("/papers/{id}/files/{fileId}/authorize")
    public ApiResponse<ContentAuthorization.FileTicket> authorize(
            @PathVariable long id,
            @PathVariable long fileId,
            @Valid @RequestBody ContentAuthorization.Request body,
            HttpServletRequest request) {
        return ApiResponse.ok(
                authorization.file(
                        id,
                        fileId,
                        body.action(),
                        TokenCodec.hash(
                                request.getRemoteAddr() + "|" + request.getHeader("User-Agent"))));
    }

    @GetMapping("/courses/{id}/chapters/{chapterId}/play-url")
    public ApiResponse<ContentAuthorization.PlaybackTicket> play(
            @PathVariable long id, @PathVariable long chapterId) {
        return ApiResponse.ok(authorization.play(id, chapterId));
    }

    @RequestMapping(
            value = "/protected-content/{token}",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<InputStreamResource> content(
            @PathVariable String token,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletRequest request)
            throws IOException {
        var c = authorization.redeem(token);
        long total = c.file().bytes(), start = 0, end = total - 1;
        if (range != null) {
            try {
                if (!range.matches("bytes=[0-9]*-[0-9]*") || range.equals("bytes=-"))
                    throw new IllegalArgumentException();
                String[] parts = range.substring(6).split("-", -1);
                if (parts[0].isEmpty()) {
                    long suffix = Long.parseLong(parts[1]);
                    if (suffix <= 0) throw new IllegalArgumentException();
                    start = Math.max(0, total - suffix);
                } else {
                    start = Long.parseLong(parts[0]);
                    if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                }
                if (start > end || start >= total) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(416)
                        .header("Content-Range", "bytes */" + total)
                        .header("Cache-Control", "no-store")
                        .build();
            }
        }
        // Transaction has ended before opening the pinned storage object or reading any bytes.
        long length = end - start + 1;
        var response =
                ResponseEntity.status(range == null ? 200 : 206)
                        .contentType(MediaType.parseMediaType(c.file().mime()))
                        .contentLength(length)
                        .header("Cache-Control", "private, no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Referrer-Policy", "no-referrer")
                        .header("Accept-Ranges", "bytes")
                        .header(
                                "Content-Disposition",
                                ContentDisposition.builder(
                                                c.action().equals("DOWNLOAD")
                                                        ? "attachment"
                                                        : "inline")
                                        .filename(c.file().name(), StandardCharsets.UTF_8)
                                        .build()
                                        .toString());
        if (range != null)
            response.header("Content-Range", "bytes " + start + "-" + end + "/" + total);
        if (request.getMethod().equals("HEAD")) return response.build();
        var bytes = assets.openProtected(c.object(), start, end);
        InputStream stream =
                new ExpiringStream(
                        bytes.stream(), clock, c.expiresAt(), () -> authorization.redeem(token));
        return response.body(new InputStreamResource(new LimitedStream(stream, length)));
    }

    /**
     * Stop emitting new chunks after expiry, including a storage read that crosses the boundary.
     */
    static final class ExpiringStream extends FilterInputStream {
        private static final java.util.concurrent.ScheduledThreadPoolExecutor TIMER;
        /**
         * Entitlement revalidation opens a transaction and locks the ledger row. Doing that for
         * every 64KB block turns one download or playback into thousands of queries, so keep the
         * authoritative check but throttle it; expiry itself is still checked on every block.
         */
        private static final long REVALIDATE_INTERVAL_MILLIS = 5_000;

        static {
            TIMER =
                    new java.util.concurrent.ScheduledThreadPoolExecutor(
                            1,
                            r -> {
                                var t = new Thread(r, "content-expiry");
                                t.setDaemon(true);
                                return t;
                            });
            TIMER.setRemoveOnCancelPolicy(true);
        }

        private final Clock clock;
        private final Instant expires;
        private final Runnable revalidate;
        private final java.util.concurrent.ScheduledFuture<?> deadline;
        private boolean revalidated;
        private long lastRevalidate;

        ExpiringStream(InputStream in, Clock clock, Instant expires) {
            this(in, clock, expires, () -> {});
        }

        ExpiringStream(InputStream in, Clock clock, Instant expires, Runnable revalidate) {
            super(in);
            this.clock = clock;
            this.expires = expires;
            this.revalidate = revalidate;
            deadline =
                    TIMER.schedule(
                            () ->
                                    Thread.startVirtualThread(
                                            () -> {
                                                try {
                                                    in.close();
                                                } catch (IOException ignored) {
                                                }
                                            }),
                            Math.max(0, Duration.between(clock.instant(), expires).toMillis()),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws IOException {
            deadline.cancel(false);
            super.close();
        }

        private void check() throws IOException {
            if (!clock.instant().isBefore(expires)) {
                close();
                throw new IOException("Content authorization expired");
            }
            long now = clock.millis();
            if (revalidated && now - lastRevalidate < REVALIDATE_INTERVAL_MILLIS) return;
            revalidated = true;
            lastRevalidate = now;
            try {
                revalidate.run();
            } catch (RuntimeException e) {
                close();
                throw new IOException("Content authorization revoked");
            }
        }

        @Override
        public int read() throws IOException {
            check();
            int n = in.read();
            check();
            return n;
        }

        @Override
        public int read(byte[] b, int offset, int count) throws IOException {
            check();
            int n = in.read(b, offset, Math.min(count, 65536));
            check();
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            check();
            long result = in.skip(n);
            check();
            return result;
        }
    }

    static final class LimitedStream extends FilterInputStream {
        private long remaining;

        LimitedStream(InputStream in, long remaining) {
            super(in);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return -1;
            int n = in.read();
            if (n != -1) remaining--;
            return n;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining == 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
