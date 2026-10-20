package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.commerce.CommerceSupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.*;
import java.util.*;

@Component
public class FinanceStore {
    public final JdbcTemplate db;
    public final Clock clock;

    public FinanceStore(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    public static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public static long n(Map<String, Object> r, String key) {
        return ((Number) r.get(key)).longValue();
    }

    public static String str(Map<String, Object> r, String key) {
        return (String) r.get(key);
    }

    public static boolean bool(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() != 0);
    }

    public static Instant time(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null
                ? null
                : v instanceof Timestamp t
                        ? t.toInstant()
                        : ((LocalDateTime) v).toInstant(ZoneOffset.UTC);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw CommerceSupport.missing();
        return rows.getFirst();
    }

    public long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(
                c -> {
                    var p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
                    return p;
                },
                key);
        return key.getKey().longValue();
    }

    public static String token() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
