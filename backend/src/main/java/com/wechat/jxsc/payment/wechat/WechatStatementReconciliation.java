package com.wechat.jxsc.payment.wechat;

import com.wechat.jxsc.common.commerce.SensitiveCipher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class WechatStatementReconciliation {
    private final JdbcTemplate db;
    private final SensitiveCipher cipher;
    private final WechatSettings settings;
    private final WechatStatements statements;

    public WechatStatementReconciliation(
            JdbcTemplate db,
            SensitiveCipher cipher,
            WechatSettings settings,
            WechatStatements statements) {
        this.db = db;
        this.cipher = cipher;
        this.settings = settings;
        this.statements = statements;
    }

    @Transactional
    public void reconcile(WechatStatements.Statement statement) {
        db.update(
                "insert ignore into wechat_statement_day(merchant_id,bill_date) values(?,?)",
                settings.merchantId(),
                statement.date());
        db.queryForObject(
                "select merchant_id from wechat_statement_day where merchant_id=? and bill_date=?"
                        + " for update",
                String.class,
                settings.merchantId(),
                statement.date());
        String availability = statement.availability();
        if (!Set.of("AVAILABLE", "ABSENT", "CREATING", "UNKNOWN").contains(availability))
            throw WechatProtocol.failure("WECHAT_STATEMENT_INVALID");
        String evidenceIdentity =
                availability.equals("AVAILABLE") ? statement.sha1() : statement.signedEvidence();
        db.update(
                "insert ignore into"
                    + " wechat_statement_availability(merchant_id,statement_type,bill_date,availability,evidence_hash,evidence_cipher)"
                    + " values(?,?,?,?,?,?)",
                settings.merchantId(),
                statement.type(),
                statement.date(),
                availability,
                com.wechat.jxsc.auth.TokenCodec.hash(availability + ":" + evidenceIdentity),
                cipher.encrypt(statement.signedEvidence()));
        if (availability.equals("CREATING") || availability.equals("UNKNOWN")) {
            issue(
                    statement,
                    "WECHAT",
                    "STATEMENT",
                    statement.type() + ":" + statement.date(),
                    "STATEMENT_COVERAGE_UNRESOLVED",
                    null,
                    null);
            if (!statement.type().equals("TRADE")) compareMissingTransfers(statement);
            return;
        }
        if (availability.equals("AVAILABLE")) {
            db.update(
                    "insert ignore into"
                        + " channel_statement(channel,merchant_id,statement_type,bill_date,hash_value,source_cipher)"
                        + " values('WECHAT',?,?,?,?,?)",
                    settings.merchantId(),
                    statement.type(),
                    statement.date(),
                    statement.sha1(),
                    cipher.encrypt(statement.source()));
            String old =
                    db.queryForObject(
                            "select hash_value from channel_statement where channel='WECHAT' and"
                                    + " merchant_id=? and statement_type=? and bill_date=?",
                            String.class,
                            settings.merchantId(),
                            statement.type(),
                            statement.date());
            if (!old.equals(statement.sha1()))
                throw WechatProtocol.failure("WECHAT_STATEMENT_CONFLICT");
        }
        var seen = new HashSet<String>();
        for (var r : statement.rows()) {
            String resource = r.resource(), business = r.businessNo(), channel = "WECHAT_PAY";
            List<Map<String, Object>> local;
            if (resource.equals("FUND")) {
                channel = "WECHAT_TRANSFER";
                local =
                        db.queryForList(
                                "select out_bill_no business_no,amount_fen,status"
                                    + " state,channel_bill_no channel_id from withdrawal_request"
                                    + " where channel='WECHAT_TRANSFER' and merchant_id=? and"
                                    + " (out_bill_no=? or channel_bill_no=?)",
                                settings.merchantId(),
                                business,
                                r.channelNo());
                if (local.size() == 1) {
                    resource = "TRANSFER";
                    business = (String) local.getFirst().get("business_no");
                }
            } else if (resource.equals("PAYMENT"))
                local =
                        db.queryForList(
                                "select amount_fen,state,channel_txn_id channel_id from"
                                        + " payment_transaction where channel='WECHAT_PAY' and"
                                        + " merchant_id=? and out_trade_no=?",
                                settings.merchantId(),
                                business);
            else
                local =
                        db.queryForList(
                                "select r.goods_fen+r.freight_fen"
                                        + " amount_fen,r.state,r.channel_refund_id channel_id from"
                                        + " refund_transaction r join payment_transaction p on"
                                        + " p.id=r.payment_id where r.channel='WECHAT_PAY' and"
                                        + " p.merchant_id=? and r.out_refund_no=?",
                                settings.merchantId(),
                                business);
            if (business.isBlank() || business.length() > 64)
                business = com.wechat.jxsc.auth.TokenCodec.hash(r.channelNo());
            String key = resource + ":" + business;
            if (!seen.add(key))
                issue(
                        statement,
                        channel,
                        resource,
                        business,
                        "DUPLICATE_CHANNEL_ROW",
                        null,
                        r.amountFen());
            if (local.size() != 1) {
                issue(
                        statement,
                        channel,
                        resource,
                        business,
                        "CHANNEL_ROW_UNMATCHED",
                        null,
                        r.amountFen());
                continue;
            }
            var l = local.getFirst();
            long amount = ((Number) l.get("amount_fen")).longValue();
            if (amount != r.amountFen())
                issue(
                        statement,
                        channel,
                        resource,
                        business,
                        "AMOUNT_MISMATCH",
                        amount,
                        r.amountFen());
            if (l.get("channel_id") != null && !l.get("channel_id").equals(r.channelNo()))
                issue(
                        statement,
                        channel,
                        resource,
                        business,
                        "CHANNEL_NUMBER_MISMATCH",
                        amount,
                        r.amountFen());
            if (resource.equals("TRANSFER")) {
                // A fund debit alone is not recipient success. Local success must come from
                // queries/callbacks.
                if (!"支出".equals(r.flow()) || !"SUCCEEDED".equals(l.get("state")))
                    issue(
                            statement,
                            channel,
                            resource,
                            business,
                            "FUND_FLOW_REQUIRES_QUERY",
                            amount,
                            r.amountFen());
            } else {
                String expected =
                        switch (r.state()) {
                            case "SUCCESS" -> "SUCCEEDED";
                            case "PROCESSING" -> "PROCESSING";
                            default -> "UNKNOWN";
                        };
                if (!expected.equals(l.get("state")))
                    issue(
                            statement,
                            channel,
                            resource,
                            business,
                            resource.equals("REFUND")
                                    ? "REFUND_SNAPSHOT_REQUIRES_QUERY"
                                    : "STATE_MISMATCH",
                            amount,
                            r.amountFen());
            }
        }
        if (statement.type().equals("TRADE")) {
            var start =
                    Timestamp.from(
                            statement.date().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
            var end =
                    Timestamp.from(
                            statement
                                    .date()
                                    .plusDays(1)
                                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                    .toInstant());
            for (var row :
                    db.queryForList(
                            "select out_trade_no business_no,amount_fen from payment_transaction"
                                    + " where channel='WECHAT_PAY' and merchant_id=? and"
                                    + " state='SUCCEEDED' and paid_at>=? and paid_at<?",
                            settings.merchantId(),
                            start,
                            end)) {
                String business = (String) row.get("business_no");
                if (!seen.contains("PAYMENT:" + business))
                    issue(
                            statement,
                            "WECHAT_PAY",
                            "PAYMENT",
                            business,
                            "LOCAL_ROW_MISSING_FROM_CHANNEL",
                            ((Number) row.get("amount_fen")).longValue(),
                            null);
            }
            // Refund statements use initiation date, not refund completion date.
            for (var row :
                    db.queryForList(
                            "select r.out_refund_no business_no,r.goods_fen+r.freight_fen"
                                + " amount_fen from refund_transaction r join payment_transaction p"
                                + " on p.id=r.payment_id where r.channel='WECHAT_PAY' and"
                                + " p.merchant_id=? and r.dispatch_started_at>=? and"
                                + " r.dispatch_started_at<?",
                            settings.merchantId(),
                            start,
                            end)) {
                String business = (String) row.get("business_no");
                if (!seen.contains("REFUND:" + business))
                    issue(
                            statement,
                            "WECHAT_PAY",
                            "REFUND",
                            business,
                            "REFUND_INITIATION_REQUIRES_QUERY",
                            ((Number) row.get("amount_fen")).longValue(),
                            null);
            }
        } else compareMissingTransfers(statement);
    }

    private void compareMissingTransfers(WechatStatements.Statement statement) {
        // The merchant/day anchor serializes availability and file imports, including first
        // imports.
        var sources =
                db.queryForList(
                        "select statement_type,hash_value,source_cipher from channel_statement"
                                + " where channel='WECHAT' and merchant_id=? and bill_date=? and"
                                + " statement_type in ('FUND_BASIC','FUND_OPERATION') for update",
                        settings.merchantId(),
                        statement.date());
        Set<String> covered = new HashSet<>();
        for (var source : sources) covered.add((String) source.get("statement_type"));
        for (var observation :
                db.queryForList(
                        "select a.statement_type,a.availability from wechat_statement_availability"
                            + " a join (select statement_type,max(id) id from"
                            + " wechat_statement_availability where merchant_id=? and bill_date=?"
                            + " group by statement_type) latest on latest.id=a.id",
                        settings.merchantId(),
                        statement.date()))
            if ("ABSENT".equals(observation.get("availability")))
                covered.add((String) observation.get("statement_type"));
        boolean complete = covered.containsAll(Set.of("FUND_BASIC", "FUND_OPERATION"));
        Set<String> businessNumbers = new HashSet<>(), channelNumbers = new HashSet<>();
        for (var source : sources) {
            String raw = cipher.decrypt((String) source.get("source_cipher"));
            var parsed =
                    statements.parse(
                            statement.date(),
                            (String) source.get("statement_type"),
                            (String) source.get("hash_value"),
                            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (var row : parsed.rows()) {
                businessNumbers.add(row.businessNo());
                channelNumbers.add(row.channelNo());
            }
        }
        var start =
                Timestamp.from(
                        statement.date().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        var end =
                Timestamp.from(
                        statement
                                .date()
                                .plusDays(1)
                                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                .toInstant());
        for (var row :
                db.queryForList(
                        "select w.out_bill_no,w.channel_bill_no,w.amount_fen from"
                            + " withdrawal_request w join commission_account_ledger l on"
                            + " l.withdrawal_id=w.id and l.event_type='WITHDRAWAL_SUCCEEDED' where"
                            + " w.channel='WECHAT_TRANSFER' and w.merchant_id=? and l.created_at>=?"
                            + " and l.created_at<?",
                        settings.merchantId(),
                        start,
                        end)) {
            String business = (String) row.get("out_bill_no");
            if (!businessNumbers.contains(business)
                    && !channelNumbers.contains(row.get("channel_bill_no")))
                // Local accounting date is not provider posting date; this is a query/review
                // candidate, never proof of missing payout or authority to correct balances.
                issue(
                        statement,
                        "WECHAT_TRANSFER",
                        "TRANSFER",
                        business,
                        complete
                                ? "TRANSFER_FUND_ROW_MISSING_REQUIRES_QUERY"
                                : "TRANSFER_STATEMENT_COVERAGE_PENDING",
                        ((Number) row.get("amount_fen")).longValue(),
                        null);
        }
    }

    private void issue(
            WechatStatements.Statement s,
            String channel,
            String type,
            String bill,
            String code,
            Long local,
            Long remote) {
        db.update(
                "insert ignore into"
                    + " reconciliation_issue(channel,business_no,resource_type,issue_code,bill_date,local_amount_fen,channel_amount_fen)"
                    + " values(?,?,?,?,?,?,?)",
                channel,
                bill,
                type,
                code,
                s.date(),
                local,
                remote);
    }
}
