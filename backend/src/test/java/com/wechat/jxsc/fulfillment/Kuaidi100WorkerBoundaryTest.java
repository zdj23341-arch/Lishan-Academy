package com.wechat.jxsc.fulfillment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.common.tasks.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class Kuaidi100WorkerBoundaryTest {
    @Test
    void stalledBodyClosesConnectionRetriesAndLetsSharedWorkerContinue() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var accepted = new AtomicReference<Socket>();
            var headers = new CountDownLatch(1);
            var peerClosed = new CompletableFuture<Boolean>();
            var executor = Executors.newFixedThreadPool(2);
            Future<Integer> work = null;
            try {
                executor.submit(
                        () -> {
                            try (var socket = server.accept()) {
                                accepted.set(socket);
                                socket.setSoTimeout(22000);
                                var input = socket.getInputStream();
                                var request = new StringBuilder();
                                int value;
                                while (!request.toString().endsWith("\r\n\r\n")
                                        && (value = input.read()) != -1)
                                    request.append((char) value);
                                int length =
                                        request.toString()
                                                .lines()
                                                .filter(
                                                        s ->
                                                                s.toLowerCase()
                                                                        .startsWith(
                                                                                "content-length:"))
                                                .mapToInt(
                                                        s ->
                                                                Integer.parseInt(
                                                                        s.substring(
                                                                                        s.indexOf(
                                                                                                        ':')
                                                                                                + 1)
                                                                                .trim()))
                                                .findFirst()
                                                .orElseThrow();
                                input.readNBytes(length);
                                socket.getOutputStream()
                                        .write(
                                                "HTTP/1.1 200 OK\r\nContent-Length: 512\r\nConnection: close\r\n\r\n"
                                                        .getBytes(StandardCharsets.US_ASCII));
                                socket.getOutputStream().flush();
                                headers.countDown();
                                // No body bytes: observe cancellation on the real peer socket.
                                try {
                                    peerClosed.complete(input.read() == -1);
                                } catch (SocketTimeoutException timeout) {
                                    peerClosed.complete(false);
                                } catch (SocketException reset) {
                                    peerClosed.complete(true);
                                }
                            } catch (Exception failure) {
                                peerClosed.completeExceptionally(failure);
                            }
                        });
                var env =
                        new MockEnvironment()
                                .withProperty("jxsc.logistics.customer", "customer")
                                .withProperty("jxsc.logistics.key", "key")
                                .withProperty(
                                        "jxsc.logistics.endpoint",
                                        "http://127.0.0.1:" + server.getLocalPort() + "/query");
                env.setActiveProfiles("test");
                var provider = new Kuaidi100Provider(env);
                var queue = mock(TaskQueue.class);
                var stalled = new TaskQueue.Lease(1, "lease1", "LOGISTICS_SYNC", "one", "{}", 0);
                var next = new TaskQueue.Lease(2, "lease2", "UNRELATED_WORK", "two", "{}", 0);
                when(queue.claim()).thenReturn(stalled, next, null);
                var nextRan = new AtomicBoolean();
                var failureCode = new AtomicReference<String>();
                TaskHandler handler =
                        new TaskHandler() {
                            public boolean supports(String type) {
                                return true;
                            }

                            public void handle(String type, String key, String payload) {
                                if (type.equals("UNRELATED_WORK")) {
                                    nextRan.set(true);
                                    return;
                                }
                                try {
                                    provider.query("yunda", "123456789");
                                } catch (ApiException unavailable) {
                                    failureCode.set(unavailable.getCode());
                                    throw unavailable;
                                }
                            }
                        };
                var worker = new TaskWorker(queue, List.of(handler));
                work = executor.submit(() -> worker.run(2));
                assertThat(headers.await(3, TimeUnit.SECONDS)).isTrue();
                var running = work;
                assertThatCode(() -> assertThat(running.get(18, TimeUnit.SECONDS)).isEqualTo(2))
                        .as(
                                "Body deadline must release the shared worker before the peer sends"
                                    + " a body")
                        .doesNotThrowAnyException();
                assertThat(failureCode.get()).isEqualTo("EXTERNAL_SERVICE_UNAVAILABLE");
                assertThat(nextRan).isTrue();
                assertThat(peerClosed.get(2, TimeUnit.SECONDS))
                        .as("Actual HTTP connection closed/cancelled")
                        .isTrue();
                verify(queue).finish(stalled, false, "TASK_RETRY", Duration.ofSeconds(5));
                verify(queue).finish(next, true, null, Duration.ZERO);
            } finally {
                if (accepted.get() != null) accepted.get().close();
                if (work != null) work.cancel(true);
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
