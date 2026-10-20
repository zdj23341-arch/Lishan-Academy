package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.common.tasks.*;
import com.wechat.jxsc.order.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class WithdrawalTransactions {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    private final FinanceStore f;
    private final CommissionService commissions;
    private final OrderService orders;
    private final DistributionAdministration admin;
    private final CommerceSupport s;
    private final SensitiveCipher cipher;
    private final TransferProvider provider;
    private final TaskQueue tasks;

    public WithdrawalTransactions(
            FinanceStore f,
            CommissionService commissions,
            OrderService orders,
            DistributionAdministration admin,
            CommerceSupport s,
            SensitiveCipher cipher,
            TransferProvider provider,
            TaskQueue tasks) {
        this.f = f;
        this.commissions = commissions;
        this.orders = orders;
        this.admin = admin;
        this.s = s;
        this.cipher = cipher;
        this.provider = provider;
        this.tasks = tasks;
    }

    public static boolean terminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(status);
    }

    @Transactional
    public FinanceDtos.WithdrawalView resolve(long id, String reason, String key) {
        return s.mapper()
                .convertValue(
                        s.execute(
                                "WITHDRAWAL_ECONOMIC_RESOLUTION",
                                key,
                                Map.of("withdrawalId", id, "reason", reason),
                                () -> {
                                    com.wechat.jxsc.payment.EconomicResolutionService
                                            .requireFinance();
                                    var first =
                                            f.one(
                                                    "select * from withdrawal_request where id=?",
                                                    id);
                                    long did = n(first, "distributor_id");
                                    guardSources(did, false);
                                    var w =
                                            f.one(
                                                    "select * from withdrawal_request where id=?"
                                                            + " for update",
                                                    id);
                                    var issue =
                                            f.one(
                                                    "select * from withdrawal_reconciliation where"
                                                            + " withdrawal_id=? for update",
                                                    id);
                                    if (str(issue, "status").equals("RESOLVED"))
                                        return view(w, false);
                                    if (!str(issue, "status").equals("NEEDS_REVIEW")
                                            || !"SUCCESS".equals(str(issue, "last_query_state"))
                                            || !Set.of("FAILED", "CLOSED")
                                                    .contains(str(w, "status")))
                                        throw CommerceSupport.conflict(
                                                "RECONCILIATION_EVIDENCE_REQUIRED",
                                                "Authenticated original-number success query is"
                                                        + " required");
                                    var expected = intent(w);
                                    var proofs =
                                            f.db.queryForList(
                                                    "select * from withdrawal_event where"
                                                        + " withdrawal_id=? and"
                                                        + " source='RECONCILIATION_QUERY' and"
                                                        + " result='SUCCEEDED' order by id desc",
                                                    id);
                                    Map<String, Object> proof = null;
                                    TransferProvider.Observation confirmed = null;
                                    for (var row : proofs) {
                                        try {
                                            var o =
                                                    s.mapper()
                                                            .readValue(
                                                                    cipher.decrypt(
                                                                            str(
                                                                                    row,
                                                                                    "detail_cipher")),
                                                                    TransferProvider.Observation
                                                                            .class);
                                            if (o.verified()
                                                    && o.state().equals("SUCCESS")
                                                    && o.amountFen() == expected.amountFen()
                                                    && Objects.equals(
                                                            o.outBillNo(), expected.outBillNo())
                                                    && Objects.equals(o.appId(), expected.appId())
                                                    && Objects.equals(
                                                            o.merchantId(), expected.merchantId())
                                                    && Objects.equals(
                                                            o.recipient(), expected.recipient())
                                                    && Objects.equals(
                                                            o.channel(), expected.channel())
                                                    && o.channelBillNo() != null
                                                    && !o.channelBillNo().isBlank()) {
                                                proof = row;
                                                confirmed = o;
                                                break;
                                            }
                                        } catch (Exception e) {
                                            throw CommerceSupport.conflict(
                                                    "RECONCILIATION_EVIDENCE_REQUIRED",
                                                    "Original query evidence cannot be verified");
                                        }
                                    }
                                    if (proof == null)
                                        throw CommerceSupport.conflict(
                                                "RECONCILIATION_EVIDENCE_REQUIRED",
                                                "Original query evidence is missing");
                                    long take =
                                            Math.min(
                                                    expected.amountFen(),
                                                    commissions
                                                            .balances(commissions.account(did))
                                                            .availableFen());
                                    commissions.change(
                                            did,
                                            "withdraw-correction:" + id,
                                            "WITHDRAWAL_CORRECTION",
                                            0,
                                            -take,
                                            0,
                                            expected.amountFen(),
                                            expected.amountFen() - take,
                                            null,
                                            null,
                                            id);
                                    f.db.update(
                                            "insert into"
                                                + " economic_resolution(resource_type,transaction_id,withdrawal_id,channel_id,observed_fen,evidence_json,actor_id,reason,request_id)"
                                                + " values('WITHDRAWAL',?,?,?,?,?,?,?,?)",
                                            id,
                                            id,
                                            confirmed.channelBillNo(),
                                            expected.amountFen(),
                                            s.encode(
                                                    Map.of(
                                                            "eventId",
                                                            proof.get("id"),
                                                            "availableDebitFen",
                                                            take,
                                                            "debtIncreaseFen",
                                                            expected.amountFen() - take)),
                                            CurrentActor.require().id(),
                                            reason,
                                            Objects.toString(
                                                    org.slf4j.MDC.get("requestId"), "internal"));
                                    f.db.update(
                                            "update withdrawal_reconciliation set"
                                                    + " status='RESOLVED',updated_at=? where"
                                                    + " withdrawal_id=?",
                                            f.now(),
                                            id);
                                    s.audit(
                                            "WITHDRAWAL_ECONOMIC_RESOLUTION",
                                            "WITHDRAWAL",
                                            id,
                                            reason,
                                            Map.of(
                                                    "retainedState",
                                                    str(w, "status"),
                                                    "observedPaidOutFen",
                                                    expected.amountFen(),
                                                    "availableDebitFen",
                                                    take,
                                                    "debtIncreaseFen",
                                                    expected.amountFen() - take,
                                                    "evidenceId",
                                                    proof.get("id")));
                                    return view(w, false);
                                }),
                        FinanceDtos.WithdrawalView.class);
    }

    static final class SourceSetChanged extends RuntimeException {}

    /**
     * Never acquire a newly discovered order while account is held: caller retries a fresh
     * transaction.
     */
    private Map<String, Object> guardSources(long did, boolean requireClear) {
        List<Long> ids =
                f.db.queryForList(
                        "select order_id from commission_ledger where distributor_id=? order by"
                                + " order_id",
                        Long.class,
                        did);
        for (long id : ids) {
            var order = orders.lock(id);
            if (requireClear) orders.requireFinancialClear(order);
            if (requireClear && order.channel.equals("DEV") && !provider.channel().equals("DEV"))
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "WITHDRAWAL_NOT_READY",
                        "Development commission cannot fund a real transfer");
        }
        var d = f.one("select * from distributor where id=? for update", did);
        commissions.account(did);
        List<Long> current =
                f.db.queryForList(
                        "select order_id from commission_ledger where distributor_id=? order by"
                                + " order_id for update",
                        Long.class,
                        did);
        if (!ids.equals(current)) throw new SourceSetChanged();
        return d;
    }

    @Transactional
    public FinanceDtos.WithdrawalView create(FinanceDtos.WithdrawalCreate r, String key) {
        lifecycle.customer();
        return s.mapper()
                .convertValue(
                        s.execute(
                                "CREATE_WITHDRAWAL",
                                key,
                                r,
                                () -> {
                                    long did = n(admin.own(), "id");
                                    var d = guardSources(did, true);
                                    var balance = commissions.balances(commissions.account(did));
                                    requireNoTransferConflict(did);
                                    if (!str(d, "status").equals("ACTIVE"))
                                        throw CommerceSupport.conflict(
                                                "DISTRIBUTOR_DISABLED", "Distributor disabled");
                                    if (bool(d, "risk_blocked"))
                                        throw CommerceSupport.conflict(
                                                "RISK_BLOCKED", "Distributor risk blocked");
                                    if (balance.debtFen() > 0)
                                        throw CommerceSupport.conflict(
                                                "OUTSTANDING_DEBT", "Outstanding commission debt");
                                    if (!provider.ready()
                                            || !bool(d, "receipt_ready")
                                            || d.get("recipient_identity_id") == null)
                                        throw new ApiException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "WITHDRAWAL_NOT_READY",
                                                "Merchant or recipient capability unavailable");
                                    var identities =
                                            f.db.queryForList(
                                                    "select * from user_wechat_identity where id=?"
                                                            + " and user_id=? and app_id=?",
                                                    n(d, "recipient_identity_id"),
                                                    n(d, "user_id"),
                                                    provider.appId());
                                    if (identities.size() != 1)
                                        throw new ApiException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "WITHDRAWAL_NOT_READY",
                                                "Current recipient identity unavailable");
                                    var limits = admin.settings().limits();
                                    if (r.amountFen() < limits.minFen()
                                            || r.amountFen()
                                                    > Math.min(
                                                            limits.maxFen(), provider.maximumFen()))
                                        throw CommerceSupport.invalid(
                                                "Amount outside withdrawal limits");
                                    var day =
                                            f.clock
                                                    .instant()
                                                    .atZone(ZoneId.of("Asia/Shanghai"))
                                                    .toLocalDate();
                                    long count =
                                            f.db.queryForList(
                                                            "select id from withdrawal_request"
                                                                + " where distributor_id=? and"
                                                                + " created_at>=? and created_at<?"
                                                                + " order by id for update",
                                                            Long.class,
                                                            did,
                                                            ts(
                                                                    day.atStartOfDay(
                                                                                    ZoneId.of(
                                                                                            "Asia/Shanghai"))
                                                                            .toInstant()),
                                                            ts(
                                                                    day.plusDays(1)
                                                                            .atStartOfDay(
                                                                                    ZoneId.of(
                                                                                            "Asia/Shanghai"))
                                                                            .toInstant()))
                                                    .size();
                                    if (count >= limits.dailyMaxCount())
                                        throw CommerceSupport.conflict(
                                                "WITHDRAWAL_LIMIT",
                                                "Daily withdrawal count reached");
                                    if (balance.availableFen() < r.amountFen())
                                        throw CommerceSupport.conflict(
                                                "INSUFFICIENT_BALANCE",
                                                "Insufficient available commission");
                                    if (r.amountFen() >= 200000
                                            && d.get("recipient_name_cipher") == null)
                                        throw new ApiException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "WECHAT_RECIPIENT_NAME_REQUIRED",
                                                "Recipient name must be provided before"
                                                        + " withdrawal");
                                    String bill = UUID.randomUUID().toString().replace("-", "");
                                    long id =
                                            f.insert(
                                                    "insert into"
                                                        + " withdrawal_request(withdrawal_no,distributor_id,amount_fen,status,out_bill_no,recipient_identity_id,recipient_cipher,app_id,merchant_id,channel,created_at,updated_at)"
                                                        + " values(?,?,?,'REQUESTED',?,?,?,?,?,?,?,?)",
                                                    bill,
                                                    did,
                                                    r.amountFen(),
                                                    bill,
                                                    n(d, "recipient_identity_id"),
                                                    cipher.encrypt(
                                                            str(identities.getFirst(), "openid")),
                                                    provider.appId(),
                                                    provider.merchantId(),
                                                    provider.channel(),
                                                    f.now(),
                                                    f.now());
                                    f.db.update(
                                            "update withdrawal_request set recipient_name_cipher=?"
                                                    + " where id=?",
                                            d.get("recipient_name_cipher"),
                                            id);
                                    commissions.change(
                                            did,
                                            "withdraw-freeze:" + id,
                                            "WITHDRAWAL_FREEZE",
                                            0,
                                            -r.amountFen(),
                                            r.amountFen(),
                                            0,
                                            0,
                                            null,
                                            null,
                                            id);
                                    tasks.enqueue(
                                            "WITHDRAWAL_TRANSFER",
                                            "" + id,
                                            "{}",
                                            f.clock.instant());
                                    tasks.enqueue(
                                            "WITHDRAWAL_ALERT",
                                            "" + id,
                                            "{}",
                                            f.clock.instant().plusSeconds(1800));
                                    s.audit(
                                            "WITHDRAWAL_REQUEST",
                                            "WITHDRAWAL",
                                            id,
                                            "Automatic commission withdrawal",
                                            Map.of("amountFen", r.amountFen()));
                                    return view(
                                            f.one(
                                                    "select * from withdrawal_request where id=?",
                                                    id),
                                            false);
                                }),
                        FinanceDtos.WithdrawalView.class);
    }

    public record Action(
            TransferProvider.Intent intent, boolean send, boolean done, Duration retry) {}

    @Transactional
    public void alertIfOverdue(long id) {
        var w = f.one("select * from withdrawal_request where id=? for update", id);
        if (str(w, "channel").equals("WECHAT_TRANSFER")
                && w.get("dispatch_started_at") != null
                && !f.clock
                        .instant()
                        .isBefore(time(w, "dispatch_started_at").plus(Duration.ofDays(29)))
                && (!terminal(str(w, "status")) || str(w, "error_code") != null)) {
            f.db.update(
                    "insert ignore into"
                        + " reconciliation_issue(channel,business_no,resource_type,issue_code,bill_date,local_amount_fen)"
                        + " values('WECHAT_TRANSFER',?,'TRANSFER','TRANSFER_QUERY_RETENTION_LIMIT',?,?)",
                    str(w, "out_bill_no"),
                    time(w, "created_at").atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),
                    n(w, "amount_fen"));
        }
        if (!terminal(str(w, "status"))
                && !bool(w, "alerted")
                && !f.clock.instant().isBefore(time(w, "created_at").plusSeconds(1800))) {
            f.db.update(
                    "update withdrawal_request set alerted=true,updated_at=?,version=version+1"
                            + " where id=?",
                    f.now(),
                    id);
            s.systemAudit(
                    "WITHDRAWAL_ALERT",
                    "WITHDRAWAL",
                    id,
                    "Transfer unresolved for 30 minutes",
                    Map.of("status", str(w, "status")));
        }
    }

    @Transactional
    public Action prepareReconciliation(long id) {
        var first = f.one("select * from withdrawal_request where id=?", id);
        guardSources(n(first, "distributor_id"), false);
        var w = f.one("select * from withdrawal_request where id=? for update", id);
        var issue =
                f.one(
                        "select * from withdrawal_reconciliation where withdrawal_id=? for update",
                        id);
        if (Set.of("NEEDS_REVIEW", "RESOLVED").contains(str(issue, "status")))
            return new Action(intent(w), false, true, Duration.ZERO);
        requireProvider(w);
        if (time(issue, "next_query_at").isAfter(f.clock.instant()))
            throw new TaskHandler.RetryLater(
                    "TRANSFER_RECONCILIATION_PENDING",
                    Duration.between(f.clock.instant(), time(issue, "next_query_at")));
        int count = (int) n(issue, "query_count");
        Duration delay = Duration.ofMinutes(count == 0 ? 1 : count == 1 ? 5 : count == 2 ? 15 : 60);
        f.db.update(
                "update withdrawal_reconciliation set"
                        + " next_query_at=?,query_count=query_count+1,updated_at=? where"
                        + " withdrawal_id=?",
                ts(f.clock.instant().plus(delay)),
                f.now(),
                id);
        return new Action(intent(w), false, false, delay);
    }

    private void requireNoTransferConflict(long did) {
        if (!f.db.queryForList(
                        "select id from withdrawal_request w where distributor_id=? and"
                            + " error_code='TRANSFER_TERMINAL_CONFLICT' and not exists(select 1"
                            + " from economic_resolution e where e.resource_type='WITHDRAWAL' and"
                            + " e.transaction_id=w.id) for update",
                        did)
                .isEmpty())
            throw CommerceSupport.conflict(
                    "FINANCIAL_RECONCILIATION_REQUIRED",
                    "Conflicting transfer evidence requires reconciliation");
    }

    private void requireProvider(Map<String, Object> w) {
        if (str(w, "channel").equals("WECHAT_TRANSFER")
                && w.get("dispatch_started_at") != null
                && !f.clock
                        .instant()
                        .isBefore(time(w, "dispatch_started_at").plus(Duration.ofDays(30))))
            throw new TaskHandler.RetryLater("TRANSFER_QUERY_WINDOW_EXPIRED", Duration.ofHours(6));
        boolean ready =
                w.get("dispatch_started_at") == null ? provider.ready() : provider.recoveryReady();
        if (!ready
                || !provider.channel().equals(str(w, "channel"))
                || !provider.appId().equals(str(w, "app_id"))
                || !provider.merchantId().equals(str(w, "merchant_id")))
            throw new TaskHandler.RetryLater(
                    "TRANSFER_PROVIDER_UNAVAILABLE", Duration.ofMinutes(5));
    }

    @Transactional
    public Action prepare(long id) {
        var first = f.one("select * from withdrawal_request where id=?", id);
        long did = n(first, "distributor_id");
        var d = guardSources(did, false);
        var w = f.one("select * from withdrawal_request where id=? for update", id);
        String state = str(w, "status");
        if (terminal(state)) return new Action(intent(w), false, true, Duration.ZERO);
        boolean send = w.get("dispatch_started_at") == null;
        if (send) {
            // All these orders are already locked by guardSources; no new order lock is acquired.
            for (long source :
                    f.db.queryForList(
                            "select order_id from commission_ledger where distributor_id=? order by"
                                    + " order_id for update",
                            Long.class,
                            did)) orders.requireFinancialClear(orders.lock(source));
            requireNoTransferConflict(did);
        }
        if (send
                && (bool(d, "risk_blocked")
                        || commissions.balances(commissions.account(did)).debtFen() > 0))
            throw new TaskHandler.RetryLater("WITHDRAWAL_RISK_HOLD", Duration.ofMinutes(5));
        requireProvider(w);
        if (send) provider.preflight(intent(w));
        if (w.get("next_query_at") != null && time(w, "next_query_at").isAfter(f.clock.instant()))
            throw new TaskHandler.RetryLater(
                    "WITHDRAWAL_QUERY_SCHEDULED",
                    Duration.between(f.clock.instant(), time(w, "next_query_at")));
        int attempts = (int) n(w, "query_count");
        Duration delay =
                Duration.ofMinutes(attempts == 0 ? 1 : attempts == 1 ? 5 : attempts == 2 ? 15 : 60);
        boolean alert = !f.clock.instant().isBefore(time(w, "created_at").plusSeconds(1800));
        if (alert && !bool(w, "alerted"))
            s.systemAudit(
                    "WITHDRAWAL_ALERT",
                    "WITHDRAWAL",
                    id,
                    "Transfer unresolved for 30 minutes",
                    Map.of("status", state));
        f.db.update(
                "update withdrawal_request set"
                    + " status='UNKNOWN',dispatch_started_at=coalesce(dispatch_started_at,?),next_query_at=?,query_count=query_count+1,alerted=?,updated_at=?,version=version+1"
                    + " where id=?",
                f.now(),
                ts(f.clock.instant().plus(delay)),
                alert,
                f.now(),
                id);
        return new Action(intent(w), send, false, delay);
    }

    public TransferProvider.Intent intent(Map<String, Object> w) {
        return new TransferProvider.Intent(
                n(w, "id"),
                str(w, "out_bill_no"),
                n(w, "amount_fen"),
                str(w, "app_id"),
                str(w, "merchant_id"),
                cipher.decrypt(str(w, "recipient_cipher")),
                str(w, "channel"),
                cipher.decrypt(str(w, "recipient_name_cipher")));
    }

    @Transactional(readOnly = true)
    public TransferProvider.Intent channelIntent(long id) {
        return intent(f.one("select * from withdrawal_request where id=?", id));
    }

    @Transactional
    public FinanceDtos.WithdrawalView requestCancel(long id, String key) {
        s.execute(
                "WITHDRAWAL_CANCEL",
                key,
                Map.of("withdrawalId", id),
                () -> {
                    owned(id);
                    var w = f.one("select * from withdrawal_request where id=? for update", id);
                    if (!str(w, "channel").equals("WECHAT_TRANSFER"))
                        throw CommerceSupport.conflict(
                                "INVALID_CHANNEL", "Real WeChat transfer required");
                    if (!terminal(str(w, "status"))) {
                        if (w.get("dispatch_started_at") == null)
                            throw CommerceSupport.conflict(
                                    "TRANSFER_NOT_DISPATCHED", "Transfer has not been dispatched");
                        f.db.update(
                                "update withdrawal_request set cancel_requested=true where id=?",
                                id);
                        tasks.enqueue("TRANSFER_CANCEL", "" + id, "{}", f.clock.instant());
                        s.audit(
                                "TRANSFER_CANCEL_REQUESTED",
                                "WITHDRAWAL",
                                id,
                                "Customer requested original transfer cancellation",
                                Map.of("status", str(w, "status")));
                    }
                    return Map.of("id", id);
                });
        return view(owned(id), false);
    }

    @Transactional
    public Action prepareCancellation(long id) {
        var w = f.one("select * from withdrawal_request where id=? for update", id);
        if (terminal(str(w, "status")) || !bool(w, "cancel_requested"))
            return new Action(intent(w), false, true, Duration.ZERO);
        requireProvider(w);
        boolean send = w.get("cancel_dispatch_started_at") == null;
        f.db.update(
                "update withdrawal_request set"
                        + " cancel_dispatch_started_at=coalesce(cancel_dispatch_started_at,?) where"
                        + " id=?",
                f.now(),
                id);
        return new Action(intent(w), send, false, Duration.ofMinutes(1));
    }

    @Transactional
    public void observe(long id, TransferProvider.Observation o, String source) {
        var first = f.one("select * from withdrawal_request where id=?", id);
        long did = n(first, "distributor_id");
        guardSources(did, false);
        var w = f.one("select * from withdrawal_request where id=? for update", id);
        var expected = intent(w);
        String hash = TokenCodec.hash(s.encode(o)),
                eventKey = expected.channel() + ":" + source + ":" + o.eventId();
        var old =
                f.db.queryForList(
                        "select payload_hash from withdrawal_event where event_key=?", eventKey);
        if (!old.isEmpty()) {
            if (!str(old.getFirst(), "payload_hash").equals(hash))
                throw CommerceSupport.conflict(
                        "TRANSFER_EVENT_CONFLICT", "Transfer event ID reused");
            return;
        }
        boolean verified =
                o.verified()
                        && Objects.equals(o.outBillNo(), expected.outBillNo())
                        && o.amountFen() == expected.amountFen()
                        && Objects.equals(o.appId(), expected.appId())
                        && Objects.equals(o.merchantId(), expected.merchantId())
                        && Objects.equals(o.recipient(), expected.recipient())
                        && Objects.equals(o.channel(), expected.channel())
                        && (w.get("channel_bill_no") == null
                                || Objects.equals(o.channelBillNo(), str(w, "channel_bill_no")));
        String target =
                !verified
                        ? "UNKNOWN"
                        : switch (o.state()) {
                            case "SUCCESS" -> "SUCCEEDED";
                            case "FAIL" -> "FAILED";
                            case "CANCELLED" -> "CLOSED";
                            case "WAIT_USER_CONFIRM" -> "USER_ACTION_REQUIRED";
                            case "ACCEPTED", "PROCESSING", "TRANSFERING", "CANCELING" ->
                                    "PROCESSING";
                            default -> "UNKNOWN";
                        };
        f.db.update(
                "insert into"
                    + " withdrawal_event(withdrawal_id,event_key,source,provider_state,result,payload_hash,detail_cipher,created_at)"
                    + " values(?,?,?,?,?,?,?,?)",
                id,
                eventKey,
                source,
                o.state(),
                verified ? target : "REJECTED",
                hash,
                cipher.encrypt(s.encode(o)),
                f.now());
        String current = str(w, "status");
        if (f.db.queryForObject(
                        "select count(*) from economic_resolution where resource_type='WITHDRAWAL'"
                                + " and transaction_id=?",
                        Long.class,
                        id)
                > 0) return;
        if (source.equals("RECONCILIATION_QUERY")) {
            String review = verified && terminal(target) ? "NEEDS_REVIEW" : "PENDING_QUERY";
            f.db.update(
                    "update withdrawal_reconciliation set"
                        + " status=?,last_query_state=?,success_observed=success_observed or"
                        + " ?,observed_paid_out_fen=greatest(observed_paid_out_fen,?),updated_at=?"
                        + " where withdrawal_id=?",
                    review,
                    verified ? o.state() : "IDENTITY_MISMATCH",
                    verified && target.equals("SUCCEEDED"),
                    verified && target.equals("SUCCEEDED") ? expected.amountFen() : 0,
                    f.now(),
                    id);
            s.systemAudit(
                    "TRANSFER_RECONCILIATION_QUERY",
                    "WITHDRAWAL",
                    id,
                    "Original transfer queried; hold retained",
                    Map.of(
                            "status",
                            review,
                            "queryState",
                            verified ? o.state() : "IDENTITY_MISMATCH"));
            return;
        }
        if (terminal(current)) {
            if (verified && target.equals("SUCCEEDED") && !current.equals("SUCCEEDED")) {
                f.db.update(
                        "update withdrawal_request set"
                            + " error_code='TRANSFER_TERMINAL_CONFLICT',alerted=true,updated_at=?,version=version+1"
                            + " where id=?",
                        f.now(),
                        id);
                f.db.update(
                        "update distributor set risk_blocked=true,reason='Conflicting terminal"
                                + " transfer evidence',version=version+1,updated_at=? where id=?",
                        f.now(),
                        did);
                f.db.update(
                        "insert into"
                            + " withdrawal_reconciliation(withdrawal_id,distributor_id,status,success_observed,observed_paid_out_fen,next_query_at,created_at,updated_at)"
                            + " values(?,?,'PENDING_QUERY',true,?,?,?,?) on duplicate key update"
                            + " success_observed=true,observed_paid_out_fen=greatest(observed_paid_out_fen,values(observed_paid_out_fen)),updated_at=values(updated_at)",
                        id,
                        did,
                        expected.amountFen(),
                        f.now(),
                        f.now(),
                        f.now());
                tasks.enqueue("TRANSFER_RECONCILE", "" + id, "{}", f.clock.instant());
            }
            if (!current.equals(target) && verified && terminal(target))
                s.systemAudit(
                        "TRANSFER_TERMINAL_CONFLICT",
                        "WITHDRAWAL",
                        id,
                        "Conflicting terminal evidence retained",
                        Map.of("retained", current, "observed", target));
            return;
        }
        if (w.get("dispatch_started_at") == null) {
            s.systemAudit(
                    "TRANSFER_UNDISPATCHED_EVIDENCE",
                    "WITHDRAWAL",
                    id,
                    "Unsolicited transfer observation",
                    Map.of("state", target));
            return;
        }
        if (target.equals("SUCCEEDED"))
            commissions.change(
                    did,
                    "withdraw-settle:" + id,
                    "WITHDRAWAL_SUCCEEDED",
                    0,
                    0,
                    -expected.amountFen(),
                    expected.amountFen(),
                    0,
                    null,
                    null,
                    id);
        if (target.equals("FAILED") || target.equals("CLOSED")) {
            long debt =
                    Math.min(
                            expected.amountFen(),
                            commissions.balances(commissions.account(did)).debtFen());
            commissions.change(
                    did,
                    "withdraw-release:" + id,
                    "WITHDRAWAL_RELEASE",
                    0,
                    expected.amountFen() - debt,
                    -expected.amountFen(),
                    0,
                    -debt,
                    null,
                    null,
                    id);
        }
        String confirmation =
                verified && target.equals("USER_ACTION_REQUIRED") && o.packageInfo() != null
                        ? cipher.encrypt(o.packageInfo())
                        : str(w, "confirmation_cipher");
        f.db.update(
                "update withdrawal_request set"
                    + " status=?,provider_state=?,channel_bill_no=coalesce(channel_bill_no,?),confirmation_cipher=?,error_code=?,next_query_at=?,version=version+1,updated_at=?"
                    + " where id=?",
                target,
                o.state(),
                verified ? o.channelBillNo() : null,
                confirmation,
                !verified
                        ? "TRANSFER_IDENTITY_MISMATCH"
                        : target.equals("FAILED")
                                ? o.safeFailureCode()
                                : target.equals("USER_ACTION_REQUIRED") && confirmation == null
                                        ? "CONFIRMATION_PACKAGE_UNAVAILABLE"
                                        : null,
                terminal(target) ? null : w.get("next_query_at"),
                f.now(),
                id);
        s.systemAudit(
                "TRANSFER_STATE",
                "WITHDRAWAL",
                id,
                source,
                Map.of("before", current, "after", target));
    }

    public FinanceDtos.WithdrawalView view(Map<String, Object> w, boolean owner) {
        var issues =
                f.db.queryForList(
                        "select * from withdrawal_reconciliation where withdrawal_id=?",
                        n(w, "id"));
        FinanceDtos.ReconciliationView reconciliation =
                issues.isEmpty()
                        ? new FinanceDtos.ReconciliationView(
                                "NONE",
                                str(w, "status").equals("SUCCEEDED") ? n(w, "amount_fen") : 0,
                                0,
                                null)
                        : new FinanceDtos.ReconciliationView(
                                str(issues.getFirst(), "status"),
                                str(w, "status").equals("SUCCEEDED")
                                                || str(issues.getFirst(), "status")
                                                        .equals("RESOLVED")
                                        ? n(w, "amount_fen")
                                        : 0,
                                n(issues.getFirst(), "observed_paid_out_fen"),
                                str(issues.getFirst(), "last_query_state"));
        FinanceDtos.Confirmation confirmation = null;
        if (owner
                && str(w, "status").equals("USER_ACTION_REQUIRED")
                && w.get("confirmation_cipher") != null)
            confirmation =
                    new FinanceDtos.Confirmation(
                            cipher.decrypt(str(w, "confirmation_cipher")),
                            str(w, "merchant_id"),
                            str(w, "app_id"));
        return new FinanceDtos.WithdrawalView(
                "" + n(w, "id"),
                "" + n(w, "distributor_id"),
                n(w, "amount_fen"),
                str(w, "status"),
                str(w, "out_bill_no"),
                str(w, "channel"),
                str(w, "provider_state"),
                str(w, "error_code"),
                time(w, "next_query_at"),
                bool(w, "alerted"),
                confirmation,
                time(w, "created_at"),
                reconciliation,
                successAt(w));
    }

    private Instant successAt(Map<String, Object> w) {
        if (!str(w, "status").equals("SUCCEEDED")) return null;
        var time =
                f.db.queryForObject(
                        "select min(created_at) from withdrawal_event where withdrawal_id=? and"
                                + " result='SUCCEEDED' and created_at>=?",
                        java.sql.Timestamp.class,
                        n(w, "id"),
                        w.get("dispatch_started_at"));
        return time == null ? null : time.toInstant();
    }

    public Map<String, Object> owned(long id) {
        long did = n(admin.own(), "id");
        return f.one("select * from withdrawal_request where id=? and distributor_id=?", id, did);
    }

    public FinanceDtos.WithdrawalView get(long id, boolean owner) {
        return view(
                owner ? owned(id) : f.one("select * from withdrawal_request where id=?", id),
                owner);
    }

    public PageResult<FinanceDtos.WithdrawalView> list(boolean owner, PageQuery q) {
        String where = owner ? " where distributor_id=" + n(admin.own(), "id") : "";
        return new PageResult<>(
                f
                        .db
                        .queryForList(
                                "select * from withdrawal_request"
                                        + where
                                        + " order by id desc limit ? offset ?",
                                q.pageSize(),
                                q.offset())
                        .stream()
                        .map(w -> view(w, false))
                        .toList(),
                q.page(),
                q.pageSize(),
                f.db.queryForObject("select count(*) from withdrawal_request" + where, Long.class));
    }

    @Transactional
    public FinanceDtos.WithdrawalView refresh(long id, boolean owner) {
        if (owner) owned(id);
        var w = f.one("select * from withdrawal_request where id=? for update", id);
        if (terminal(str(w, "status"))) return view(w, owner);
        if (w.get("last_refresh_at") != null
                && time(w, "last_refresh_at").plusSeconds(60).isAfter(f.clock.instant()))
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Refresh once per minute");
        f.db.update(
                "update withdrawal_request set last_refresh_at=?,next_query_at=?,updated_at=? where"
                        + " id=?",
                f.now(),
                f.now(),
                f.now(),
                id);
        tasks.enqueue("WITHDRAWAL_TRANSFER", "" + id, "{}", f.clock.instant());
        f.db.update(
                "update outbox_task set next_run_at=?,status=case when status='DONE' then 'READY'"
                    + " else status end where task_type='WITHDRAWAL_TRANSFER' and business_key=?",
                f.now(),
                "" + id);
        var after = f.one("select * from withdrawal_request where id=? for update", id);
        if (!owner)
            s.audit(
                    "WITHDRAWAL_REFRESH",
                    "WITHDRAWAL",
                    id,
                    "Administrator requested original transfer query",
                    refreshSummary(w),
                    refreshSummary(after));
        return view(after, owner);
    }

    private Map<String, Object> refreshSummary(Map<String, Object> w) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "" + n(w, "id"));
        result.put("status", str(w, "status"));
        result.put("lastRefreshAt", time(w, "last_refresh_at"));
        result.put("nextQueryAt", time(w, "next_query_at"));
        return result;
    }
}
