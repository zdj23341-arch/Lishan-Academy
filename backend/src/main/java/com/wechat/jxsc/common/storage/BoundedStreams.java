package com.wechat.jxsc.common.storage;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;

final class BoundedStreams {
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "storage-deadlines");
                        t.setDaemon(true);
                        return t;
                    });

    static InputStream deadline(InputStream source, long bytes, Duration timeout) {
        return new FilterInputStream(source) {
            long remaining = bytes;
            final ScheduledFuture<?> closeTask =
                    TIMER.schedule(
                            () -> {
                                try {
                                    source.close();
                                } catch (IOException ignored) {
                                }
                            },
                            timeout.toMillis(),
                            TimeUnit.MILLISECONDS);

            public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) == -1 ? -1 : b[0] & 255;
            }

            public int read(byte[] b, int o, int n) throws IOException {
                if (n == 0) return 0;
                if (remaining == 0) return -1;
                int got = source.read(b, o, (int) Math.min(n, remaining));
                if (got == -1) throw new EOFException("Truncated object");
                remaining -= got;
                return got;
            }

            public long skip(long n) throws IOException {
                long skipped = source.skip(Math.min(n, remaining));
                remaining -= skipped;
                return skipped;
            }

            public void close() throws IOException {
                closeTask.cancel(false);
                super.close();
            }
        };
    }
}
