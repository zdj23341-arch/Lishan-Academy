package com.wechat.jxsc.common.tasks;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class TaskWorker {
    private final TaskQueue queue;
    private final List<TaskHandler> handlers;

    public TaskWorker(TaskQueue queue, List<TaskHandler> handlers) {
        this.queue = queue;
        this.handlers = handlers;
    }

    public int run(int max) {
        int count = 0;
        while (count < max) {
            var lease = queue.claim();
            if (lease == null) break;
            count++;
            try {
                var matching = handlers.stream().filter(h -> h.supports(lease.type())).toList();
                if (matching.size() != 1)
                    throw new TaskHandler.RetryLater("CONSUMER_PENDING", Duration.ofMinutes(5));
                matching.getFirst().handle(lease.type(), lease.key(), lease.payload());
                queue.finish(lease, true, null, Duration.ZERO);
            } catch (TaskHandler.RetryLater e) {
                queue.finish(lease, false, e.getMessage(), e.delay);
            } catch (Exception e) {
                queue.finish(
                        lease,
                        false,
                        "TASK_RETRY",
                        Duration.ofSeconds(
                                Math.min(3600, 5L * (1L << Math.min(lease.attempts(), 9)))));
            }
        }
        return count;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "jxsc.tasks.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
        private final TaskWorker worker;

        Scheduling(TaskWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${jxsc.tasks.poll-ms:5000}")
        void poll() {
            worker.run(20);
        }
    }
}
