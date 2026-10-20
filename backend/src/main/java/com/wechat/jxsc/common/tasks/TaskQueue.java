package com.wechat.jxsc.common.tasks;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class TaskQueue {
    @PersistenceContext private EntityManager em;
    private final Clock clock;

    public TaskQueue(Clock clock) {
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String type, String key, String payload, Instant due) {
        em.createNativeQuery(
                        "INSERT INTO"
                            + " outbox_task(task_type,business_key,payload_json,status,retry_count,next_run_at,created_at,updated_at,version)"
                            + " VALUES (:t,:k,:p,'READY',0,:d,:n,:n,0) ON DUPLICATE KEY UPDATE"
                            + " id=id")
                .setParameter("t", type)
                .setParameter("k", key)
                .setParameter("p", payload)
                .setParameter("d", Timestamp.from(due))
                .setParameter("n", Timestamp.from(clock.instant()))
                .executeUpdate();
    }

    public record Lease(
            long id, String token, String type, String key, String payload, int attempts) {}

    @Transactional
    public Lease claim() {
        var rows =
                em.createNativeQuery(
                                "SELECT * FROM outbox_task WHERE next_run_at<=:n AND"
                                    + " (status='READY' OR (status='RUNNING' AND locked_until<=:n))"
                                    + " ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                                OutboxTask.class)
                        .setParameter("n", Timestamp.from(clock.instant()))
                        .getResultList();
        if (rows.isEmpty()) return null;
        var t = (OutboxTask) rows.getFirst();
        t.status = "RUNNING";
        t.leaseToken = UUID.randomUUID().toString();
        t.lockedUntil = clock.instant().plusSeconds(120);
        em.flush();
        return new Lease(
                t.getId(), t.leaseToken, t.taskType, t.businessKey, t.payloadJson, t.retryCount);
    }

    @Transactional
    public void finish(Lease lease, boolean done, String safeError, Duration retry) {
        var t = em.find(OutboxTask.class, lease.id(), LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(t.leaseToken, lease.token())) return;
        t.status = done ? "DONE" : "READY";
        t.lockedUntil = null;
        t.leaseToken = null;
        t.lastError = safeError;
        if (!done) {
            t.retryCount++;
            t.nextRunAt = clock.instant().plus(retry);
        }
    }
}
