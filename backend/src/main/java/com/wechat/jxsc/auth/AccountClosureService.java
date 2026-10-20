package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.membership.EntitlementLedger;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class AccountClosureService {
    private final JdbcTemplate db;
    private final AccountLifecycle lifecycle;
    private final CommerceSupport support;
    private final SensitiveCipher cipher;
    private final EntitlementLedger ledger;
    private final Environment env;
    private final Clock clock;

    public AccountClosureService(
            JdbcTemplate db,
            AccountLifecycle lifecycle,
            CommerceSupport support,
            SensitiveCipher cipher,
            EntitlementLedger ledger,
            Environment env,
            Clock clock) {
        this.db = db;
        this.lifecycle = lifecycle;
        this.support = support;
        this.cipher = cipher;
        this.ledger = ledger;
        this.env = env;
        this.clock = clock;
    }

    private static ApiException obligations() {
        return CommerceSupport.conflict(
                "ACCOUNT_OBLIGATIONS_PENDING",
                "Resolve pending orders, aftersales, financial holds, balances and transfers before"
                        + " closing");
    }

    private long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountClosureController.Closed close(
            AccountClosureController.Close request, String key) {
        lifecycle.customer();
        long user = CurrentActor.require().id();
        return support.mapper()
                .convertValue(
                        support.execute(
                                "CLOSE_ACCOUNT",
                                key,
                                request,
                                () -> closeLocked(user, request.forfeitRemainingAccess())),
                        AccountClosureController.Closed.class);
    }

    private Object closeLocked(long user, boolean forfeit) {
        // Admission prevents new owned orders. Existing provider callbacks serialize on these
        // orders.
        var distributors =
                db.queryForList("select id from distributor where user_id=?", Long.class, user);
        Long distributor = distributors.isEmpty() ? null : distributors.getFirst();
        var orders =
                new TreeSet<>(
                        db.queryForList(
                                "select id from store_order where user_id=?", Long.class, user));
        if (distributor != null)
            orders.addAll(
                    db.queryForList(
                            "select order_id from attribution where distributor_id=?",
                            Long.class,
                            distributor));
        for (long order : orders)
            db.queryForObject(
                    "select id from store_order where id=? for update", Long.class, order);
        if (distributor != null) {
            db.queryForObject(
                    "select id from distributor where id=? for update", Long.class, distributor);
            var account =
                    db.queryForMap(
                            "select * from commission_account where distributor_id=? for update",
                            distributor);
            // paid_out is cumulative history, not an outstanding balance.
            for (String field :
                    List.of("pending_fen", "available_fen", "withdrawing_fen", "debt_fen"))
                if (((Number) account.get(field)).longValue() != 0) throw obligations();
            var current =
                    db.queryForList(
                            "select order_id from attribution where distributor_id=?",
                            Long.class,
                            distributor);
            if (!orders.containsAll(current))
                throw CommerceSupport.conflict(
                        "ACCOUNT_SOURCE_CHANGED", "Related orders changed; retry closure");
            db.queryForList(
                    "select id from withdrawal_request where distributor_id=? order by id for"
                            + " update",
                    Long.class,
                    distributor);
            if (count(
                                    "select count(*) from withdrawal_request where distributor_id=?"
                                            + " and status not in ('SUCCEEDED','FAILED','CLOSED')",
                                    distributor)
                            > 0
                    || count(
                                    "select count(*) from withdrawal_reconciliation where"
                                            + " distributor_id=? and status<>'RESOLVED'",
                                    distributor)
                            > 0) throw obligations();
            for (long source : current) {
                requireSettledOrder(source);
                // The receipt is authoritative: effects and receipt commit atomically. Task
                // status alone can lag a completed effect or omit a committed, unconsumed event.
                if (count(
                                "select count(*) from outbox_task t where t.task_type in"
                                    + " ('ORDER_PAID','ORDER_COMPLETED','AFTERSALE_OPENED','AFTERSALE_CLOSED','REFUND_SUCCEEDED')"
                                    + " and (case when t.task_type in"
                                    + " ('ORDER_PAID','ORDER_COMPLETED') then t.business_key else"
                                    + " json_unquote(json_extract(t.payload_json,'$.orderId'))"
                                    + " end)=? and not exists(select 1 from commerce_event_receipt"
                                    + " r where r.event_type=t.task_type and"
                                    + " r.business_key=t.business_key and"
                                    + " r.consumer_name='COMMISSIONS')",
                                Long.toString(source))
                        > 0) throw obligations();
            }
        }
        for (long owned :
                db.queryForList("select id from store_order where user_id=?", Long.class, user))
            requireSettledOrder(owned);
        if (count("select count(*) from store_order where user_id=? and financial_hold=true", user)
                        > 0
                || count(
                                "select count(*) from aftersale_case where user_id=? and status in"
                                    + " ('REQUESTED','RETURN_REQUIRED','RETURNING','PROCESSING')",
                                user)
                        > 0
                || count(
                                "select count(*) from payment_transaction p join store_order o on"
                                        + " o.id=p.order_id where o.user_id=? and p.state not in"
                                        + " ('SUCCEEDED','FAILED','CLOSED')",
                                user)
                        > 0
                || count(
                                "select count(*) from refund_transaction r join store_order o on"
                                        + " o.id=r.order_id where o.user_id=? and r.state not in"
                                        + " ('SUCCEEDED','FAILED','CLOSED') and"
                                        + " r.dispatch_suppressed=false",
                                user)
                        > 0) throw obligations();
        // All grant writers use order -> subject. No later order/account lock is taken below.
        db.update("insert ignore into entitlement_subject(user_id) values(?)", user);
        ledger.lock(user);
        long grants =
                count(
                        "select count(*) from entitlement e where e.user_id=? and (e.expires_at is"
                                + " null or e.expires_at>?) and not exists(select 1 from"
                                + " entitlement_event v where v.source_key=e.source_key and"
                                + " v.event_type='REVOKE')",
                        user,
                        Timestamp.from(clock.instant()));
        if (grants > 0 && !forfeit)
            throw CommerceSupport.conflict(
                    "ACCOUNT_ACCESS_FORFEIT_REQUIRED",
                    "Explicitly confirm giving up remaining course and membership access; no refund"
                            + " is created");
        ledger.forfeit(user);
        var identities =
                db.queryForList(
                        "select app_id,openid,unionid from user_wechat_identity where user_id=? for"
                                + " update",
                        user);
        var addresses =
                db.queryForList(
                        "select a.* from order_address_snapshot a join store_order o on"
                                + " o.id=a.order_id where o.user_id=?",
                        user);
        var aftersales =
                db.queryForList(
                        "select"
                            + " id,order_id,reason,return_address_cipher,return_carrier_code,return_tracking_no"
                            + " from aftersale_case where user_id=?",
                        user);
        var shipments =
                db.queryForList(
                        "select p.* from shipment p join store_order o on o.id=p.order_id where"
                                + " o.user_id=?",
                        user);
        var receipt =
                distributor == null
                        ? List.of()
                        : db.queryForList(
                                "select recipient_identity_id,recipient_name_cipher from"
                                        + " distributor where id=?",
                                distributor);
        boolean fiscal =
                count(
                                        "select count(*) from store_order where user_id=? and"
                                                + " paid_at is not null",
                                        user)
                                > 0
                        || count(
                                        "select count(*) from payment_transaction p join"
                                            + " store_order o on o.id=p.order_id where o.user_id=?"
                                            + " and p.state='SUCCEEDED'",
                                        user)
                                > 0
                        || count(
                                        "select count(*) from refund_transaction r join store_order"
                                                + " o on o.id=r.order_id where o.user_id=? and"
                                                + " r.state='SUCCEEDED'",
                                        user)
                                > 0
                        || distributor != null
                                && (count(
                                                        "select count(*) from"
                                                                + " commission_account_ledger where"
                                                                + " distributor_id=?",
                                                        distributor)
                                                > 0
                                        || count(
                                                        "select count(*) from withdrawal_request"
                                                                + " where distributor_id=?",
                                                        distributor)
                                                > 0);
        if (fiscal) {
            int days = env.getProperty("jxsc.privacy.retention-days", Integer.class, 0);
            if (days <= 0 || days > 36500)
                throw CommerceSupport.conflict(
                        "PRIVACY_RETENTION_NOT_CONFIGURED",
                        "A verified financial retention policy must be configured before closing"
                                + " this account");
            db.update(
                    "insert into retained_personal_data(user_id,payload_cipher,retain_until)"
                            + " values(?,?,?)",
                    user,
                    cipher.encrypt(
                            support.encode(
                                    Map.of(
                                            "identities",
                                            identities,
                                            "addresses",
                                            addresses,
                                            "aftersales",
                                            aftersales,
                                            "receipt",
                                            receipt,
                                            "shipments",
                                            shipments))),
                    Timestamp.from(clock.instant().plus(Duration.ofDays(days))));
        }
        String erased = cipher.encrypt("ANONYMIZED");
        db.update(
                "update user_account set"
                    + " nickname='ANONYMIZED',avatar_url=null,phone_cipher=null,status='ANONYMIZED',version=version+1,updated_at=?"
                    + " where id=?",
                Timestamp.from(clock.instant()),
                user);
        db.update(
                "update user_wechat_identity set"
                    + " app_id=concat('CLOSED_',id),openid=concat('CLOSED_',id),unionid=null,version=version+1,updated_at=?"
                    + " where user_id=?",
                Timestamp.from(clock.instant()),
                user);
        db.update(
                "update user_address set"
                    + " name_cipher=?,phone_cipher=?,detail_cipher=?,province_code='000000',city_code='000000',district_code='000000',is_default=false,deleted=true,version=version+1"
                    + " where user_id=?",
                erased,
                erased,
                erased,
                user);
        db.update(
                "update order_address_snapshot a join store_order o on o.id=a.order_id set"
                    + " a.name_cipher=?,a.phone_cipher=?,a.detail_cipher=?,a.province_code='000000',a.city_code='000000',a.district_code='000000'"
                    + " where o.user_id=?",
                erased,
                erased,
                erased,
                user);
        db.update(
                "update aftersale_case set"
                    + " reason='ANONYMIZED',return_address_cipher=null,return_tracking_no=null,version=version+1"
                    + " where user_id=?",
                user);
        db.update("update referral_touch set visitor_key_hash=null where user_id=?", user);
        db.update(
                "update shipment p join store_order o on o.id=p.order_id set"
                    + " p.tracking_no='ANONYMIZED',p.events_json='[]',p.version=p.version+1 where"
                    + " o.user_id=?",
                user);
        db.update(
                "update login_session set revoked_at=coalesce(revoked_at,?),device_hash=? where"
                        + " user_id=?",
                Timestamp.from(clock.instant()),
                TokenCodec.hash("ANONYMIZED"),
                user);
        db.update(
                "update file_asset set status='DISABLED',version=version+1 where"
                        + " owner_type='CUSTOMER' and owner_id=?",
                user);
        if (distributor != null) {
            db.update(
                    "update distributor set"
                        + " status='DISABLED',risk_blocked=true,receipt_ready=false,recipient_name_cipher=null,reason='ACCOUNT_CLOSED',version=version+1"
                        + " where id=?",
                    distributor);
            db.update("update referral_code set enabled=false where distributor_id=?", distributor);
        }
        db.update("update account_lifecycle set state='CLOSED' where user_id=?", user);
        db.update(
                "insert into account_closure(user_id,forfeited_access,request_id) values(?,?,?)",
                user,
                forfeit,
                Objects.toString(org.slf4j.MDC.get("requestId"), "internal"));
        support.audit(
                "ACCOUNT_ANONYMIZED",
                "USER",
                user,
                "Customer confirmed account closure",
                Map.of("forfeitedAccess", forfeit, "financialHistoryRetained", fiscal));
        return new AccountClosureController.Closed(Long.toString(user), "ANONYMIZED");
    }

    /** Caller holds every relevant order lock before the account lock. Never takes a new lock. */
    private void requireSettledOrder(long order) {
        if (count(
                                "select count(*) from store_order o where o.id=? and ("
                                    + " (o.order_status not in ('COMPLETED','CANCELLED') and not"
                                    + " (o.order_status='PAID' and o.refund_status='FULL' and"
                                    + " o.payable_amount_fen>0 and"
                                    + " o.refunded_goods_fen+o.refunded_freight_fen>=o.payable_amount_fen))"
                                    + " or o.financial_hold=true or o.active_aftersale_id is not"
                                    + " null or o.refund_status in"
                                    + " ('REQUESTED','RETURN_REQUIRED','RETURNING','PROCESSING'))",
                                order)
                        > 0
                || count(
                                "select count(*) from aftersale_case where order_id=? and status in"
                                    + " ('REQUESTED','RETURN_REQUIRED','RETURNING','PROCESSING')",
                                order)
                        > 0
                || count(
                                "select count(*) from payment_transaction where order_id=? and"
                                        + " state not in ('SUCCEEDED','FAILED','CLOSED')",
                                order)
                        > 0
                || count(
                                "select count(*) from refund_transaction where order_id=? and state"
                                        + " not in ('SUCCEEDED','FAILED','CLOSED') and"
                                        + " dispatch_suppressed=false",
                                order)
                        > 0
                || count(
                                "select count(*) from financial_reconciliation where order_id=? and"
                                        + " status<>'RESOLVED'",
                                order)
                        > 0) throw obligations();
    }

    public record Retained(
            String userId, Instant retainUntil, com.fasterxml.jackson.databind.JsonNode data) {}

    @Transactional
    public Retained retained(long user, String reason) {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        if (!actor.permissions().contains("FINANCIAL_RETENTION_READ"))
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "Financial retention permission required");
        var rows =
                db.queryForList(
                        "select * from retained_personal_data where user_id=? and retain_until>?",
                        user,
                        Timestamp.from(clock.instant()));
        if (rows.isEmpty()) throw CommerceSupport.missing();
        var row = rows.getFirst();
        var data = support.decode(cipher.decrypt((String) row.get("payload_cipher")));
        for (var a : data.path("addresses"))
            for (String field : List.of("name_cipher", "phone_cipher", "detail_cipher"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) a)
                        .put(field.replace("_cipher", ""), cipher.decrypt(a.path(field).asText()));
        for (var a : data.path("addresses"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) a)
                    .remove(List.of("name_cipher", "phone_cipher", "detail_cipher"));
        stringifyRetainedIdentifiers(data);
        support.audit(
                "FINANCIAL_RETENTION_READ",
                "USER",
                user,
                reason,
                Map.of("purpose", "Retained financial personal data"));
        return new Retained(
                Long.toString(user),
                com.wechat.jxsc.distribution.FinanceStore.time(row, "retain_until"),
                data);
    }

    /**
     * Normalize at the response boundary so existing encrypted JDBC-map payloads remain readable.
     */
    private void stringifyRetainedIdentifiers(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            object.properties()
                    .forEach(
                            field -> {
                                String name = field.getKey();
                                var value = field.getValue();
                                if ((name.equals("id")
                                                || name.endsWith("_id")
                                                || name.endsWith("Id"))
                                        && value.isIntegralNumber()) {
                                    object.put(name, value.bigIntegerValue().toString());
                                } else {
                                    stringifyRetainedIdentifiers(value);
                                }
                            });
        } else if (node.isArray()) {
            node.forEach(this::stringifyRetainedIdentifiers);
        }
    }
}
