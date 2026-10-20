package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

@Service
public class LoginRateLimiter {
    @PersistenceContext private EntityManager em;
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    // Independent transaction: failed authentication must not roll back the attempt.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void customer(String remote) {
        if (!attempt("wechat-ip", remote, 100))
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts; retry later");
    }

    private boolean attempt(String namespace, String key, int maximum) {
        String hash = TokenCodec.hash(namespace + ":" + key);
        var now = clock.instant();
        em.createNativeQuery(
                        "INSERT INTO login_rate_limit(bucket_hash,window_start,attempts) VALUES"
                                + " (:key,:now,0) ON DUPLICATE KEY UPDATE bucket_hash=bucket_hash")
                .setParameter("key", hash)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
        Object[] row =
                (Object[])
                        em.createNativeQuery(
                                        "SELECT window_start,attempts FROM login_rate_limit WHERE"
                                                + " bucket_hash=:key FOR UPDATE")
                                .setParameter("key", hash)
                                .getSingleResult();
        var start = ((Timestamp) row[0]).toInstant();
        int count = ((Number) row[1]).intValue();
        if (!start.plus(Duration.ofMinutes(15)).isAfter(now)) {
            start = now;
            count = 0;
        }
        boolean allowed = count < maximum;
        em.createNativeQuery(
                        "UPDATE login_rate_limit SET window_start=:start,attempts=:count WHERE"
                                + " bucket_hash=:key")
                .setParameter("start", Timestamp.from(start))
                .setParameter("count", Math.min(count + 1, maximum))
                .setParameter("key", hash)
                .executeUpdate();
        return allowed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String name, String remote) {
        boolean ip = attempt("admin-ip", remote, 50);
        boolean account = attempt("admin-name", name, 5);
        if (!ip || !account)
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts; retry later");
    }
}
