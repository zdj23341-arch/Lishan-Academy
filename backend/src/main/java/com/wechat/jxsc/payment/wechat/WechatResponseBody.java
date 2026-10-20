package com.wechat.jxsc.payment.wechat;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Bounds memory and elapsed time through the last response byte, including stalled bodies. */
public final class WechatResponseBody {
    private WechatResponseBody() {}

    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maximum)
            throws Exception {
        var pending = client.sendAsync(request, info -> new Limited(maximum));
        try {
            return pending.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private final int maximum;
        private long received;
        private Flow.Subscription subscription;
        private boolean rejected;

        Limited(int maximum) {
            this.maximum = maximum;
        }

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (rejected) return;
            for (var buffer : buffers) received += buffer.remaining();
            if (received > maximum) {
                rejected = true;
                subscription.cancel();
                delegate.onError(WechatProtocol.failure("WECHAT_RESPONSE_TOO_LARGE"));
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable error) {
            if (!rejected) delegate.onError(error);
        }

        public void onComplete() {
            if (!rejected) delegate.onComplete();
        }
    }
}
