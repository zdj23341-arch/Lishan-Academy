package com.wechat.jxsc.paper;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ProtectedStreamDeadlineTest {
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void revalidatesAtMostOncePerIntervalWhileStillCheckingExpiryEveryBlock() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-07T00:00:00Z"));
        var calls = new AtomicInteger();
        try (var stream =
                new ProtectedContentController.ExpiringStream(
                        new ByteArrayInputStream(new byte[300_000]),
                        clock,
                        clock.instant().plusSeconds(60),
                        calls::incrementAndGet)) {
            var buffer = new byte[65536];
            stream.read(buffer, 0, buffer.length);
            stream.read(buffer, 0, buffer.length);
            assertThat(calls).hasValue(1);
            clock.advance(Duration.ofSeconds(5));
            stream.read(buffer, 0, buffer.length);
            assertThat(calls).hasValue(2);
        }
    }
    @Test
    void expiryClosesBlockedStorageRead() throws Exception {
        var closed = new CountDownLatch(1);
        InputStream source =
                new InputStream() {
                    public int read() throws IOException {
                        try {
                            if (!closed.await(2, TimeUnit.SECONDS))
                                throw new IOException("Read did not cancel");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        return 1;
                    }

                    public void close() {
                        closed.countDown();
                    }
                };
        try (var stream =
                new ProtectedContentController.ExpiringStream(
                        source, Clock.systemUTC(), Instant.now().plusMillis(100))) {
            assertThatThrownBy(stream::read)
                    .isInstanceOf(IOException.class)
                    .hasMessage("Content authorization expired");
            assertThat(closed.getCount()).isZero();
        }
    }
}
