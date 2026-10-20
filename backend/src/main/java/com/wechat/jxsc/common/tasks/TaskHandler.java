package com.wechat.jxsc.common.tasks;

import java.time.Duration;

/**
 * Called without a transaction. Domain work starts its own transaction and deduplicates by business
 * key.
 */
public interface TaskHandler {
    boolean supports(String type);

    void handle(String type, String businessKey, String payloadJson);

    final class RetryLater extends RuntimeException {
        public final Duration delay;

        public RetryLater(String safeCode, Duration delay) {
            super(safeCode);
            this.delay = delay;
        }
    }
}
