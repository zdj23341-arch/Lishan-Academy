package com.wechat.jxsc.payment.wechat;

import com.wechat.jxsc.auth.TokenCodec;
import com.wechat.jxsc.common.commerce.SensitiveCipher;
import com.wechat.jxsc.common.tasks.TaskQueue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Clock;

@Service
public class WechatEvidence {
    private final JdbcTemplate db;
    private final SensitiveCipher cipher;
    private final TaskQueue tasks;
    private final Clock clock;

    public WechatEvidence(JdbcTemplate db, SensitiveCipher cipher, TaskQueue tasks, Clock clock) {
        this.db = db;
        this.cipher = cipher;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long record(
            String channel,
            String type,
            String bill,
            String event,
            String canonical,
            String protectedPayload,
            boolean callback) {
        String hash = TokenCodec.hash(canonical);
        db.update(
                "insert ignore into"
                    + " wechat_evidence(channel,resource_type,business_no,event_key,payload_hash,payload_cipher)"
                    + " values(?,?,?,?,?,?)",
                channel,
                type,
                bill,
                event,
                hash,
                cipher.encrypt(protectedPayload));
        var row =
                db.queryForMap(
                        "select id,payload_hash from wechat_evidence where channel=? and"
                                + " event_key=?",
                        channel,
                        event);
        if (!hash.equals(row.get("payload_hash")))
            throw WechatProtocol.failure("WECHAT_EVENT_CONFLICT");
        long id = ((Number) row.get("id")).longValue();
        if (callback) tasks.enqueue("WECHAT_CALLBACK", "" + id, "{}", clock.instant());
        return id;
    }

    @Transactional(readOnly = true)
    public String payload(long id) {
        return cipher.decrypt(
                db.queryForObject(
                        "select payload_cipher from wechat_evidence where id=?", String.class, id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void issue(long id, String code) {
        var row =
                db.queryForMap(
                        "select channel,resource_type,business_no from wechat_evidence where id=?",
                        id);
        db.update(
                "insert ignore into"
                    + " reconciliation_issue(channel,business_no,resource_type,issue_code,bill_date)"
                    + " values(?,?,?,?,?)",
                row.get("channel"),
                row.get("business_no"),
                row.get("resource_type"),
                code,
                java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))));
    }
}
