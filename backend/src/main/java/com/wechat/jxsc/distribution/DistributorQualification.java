package com.wechat.jxsc.distribution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DistributorQualification {
    private final JdbcTemplate db;

    public DistributorQualification(JdbcTemplate db) {
        this.db = db;
    }

    public boolean enabled(long userId) {
        return db.queryForObject(
                        "select count(*) from distributor where user_id=? and status='ACTIVE'",
                        Long.class,
                        userId)
                > 0;
    }
}
