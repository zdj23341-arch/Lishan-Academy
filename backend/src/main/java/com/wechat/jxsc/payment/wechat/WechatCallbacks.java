package com.wechat.jxsc.payment.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import com.fasterxml.jackson.databind.*;
import com.wechat.jxsc.common.tasks.TaskHandler;
import com.wechat.jxsc.distribution.*;
import com.wechat.jxsc.distribution.wechat.WechatTransferProvider;
import com.wechat.jxsc.payment.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WechatCallbacks implements TaskHandler {
    private final WechatHttp http;
    private final ObjectMapper json;
    private final WechatEvidence evidence;
    private final PaymentProvider payments;
    private final TransferProvider transfers;
    private final PaymentTransactions tx;
    private final RefundTransactions refunds;
    private final PaymentInbox inbox;
    private final WithdrawalTransactions withdrawals;
    private final JdbcTemplate db;

    public WechatCallbacks(
            WechatHttp http,
            ObjectMapper json,
            WechatEvidence evidence,
            PaymentProvider payments,
            TransferProvider transfers,
            PaymentTransactions tx,
            RefundTransactions refunds,
            PaymentInbox inbox,
            WithdrawalTransactions withdrawals,
            JdbcTemplate db) {
        this.http = http;
        this.json = json;
        this.evidence = evidence;
        this.payments = payments;
        this.transfers = transfers;
        this.tx = tx;
        this.refunds = refunds;
        this.inbox = inbox;
        this.withdrawals = withdrawals;
        this.db = db;
    }

    public void receive(String kind, byte[] bytes, Map<String, String> headers) {
        if (bytes.length > 1024 * 1024) throw failure("WECHAT_RESPONSE_TOO_LARGE");
        http.crypto().verify(bytes, headers);
        try {
            var envelope = json.readTree(bytes);
            String event = text(envelope, "id"), type = text(envelope, "event_type");
            if (!"encrypt-resource".equals(text(envelope, "resource_type")))
                throw failure("WECHAT_INVALID_RESOURCE");
            boolean transfer = "transfers".equals(kind), refund = "refunds".equals(kind);
            if (transfer
                    ? !"MCHTRANSFER.BILL.FINISHED".equals(type)
                    : refund
                            ? !Set.of("REFUND.SUCCESS", "REFUND.CLOSED", "REFUND.ABNORMAL")
                                    .contains(type)
                            : !"TRANSACTION.SUCCESS".equals(type))
                throw failure("WECHAT_EVENT_TYPE_MISMATCH");
            JsonNode resource = json.readTree(http.crypto().decrypt(envelope.path("resource")));
            String bill =
                    text(
                            resource,
                            transfer ? "out_bill_no" : refund ? "out_refund_no" : "out_trade_no");
            if (event.length() > 100
                    || !bill.matches(
                            transfer
                                    ? "[A-Za-z0-9]{1,32}"
                                    : refund
                                            ? "[A-Za-z0-9_\\-|*@]{1,64}"
                                            : "[A-Za-z0-9_\\-|*]{6,32}"))
                throw failure("WECHAT_INVALID_RESOURCE");
            String protectedPayload =
                    json.writeValueAsString(
                            Map.of(
                                    "kind",
                                    kind,
                                    "eventId",
                                    event,
                                    "resource",
                                    resource,
                                    "signedBody",
                                    Base64.getEncoder().encodeToString(bytes),
                                    "signatureHeaders",
                                    headers));
            long id =
                    evidence.record(
                            transfer ? "WECHAT_TRANSFER" : "WECHAT_PAY",
                            transfer ? "TRANSFER" : refund ? "REFUND" : "PAYMENT",
                            bill,
                            "CALLBACK:" + event,
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                            protectedPayload,
                            true);
            process(id);
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw failure("WECHAT_CALLBACK_UNRESOLVED");
        }
    }

    public boolean supports(String type) {
        return "WECHAT_CALLBACK".equals(type);
    }

    public void handle(String type, String key, String payload) {
        process(Long.parseLong(key));
    }

    public void process(long id) {
        try {
            var data = json.readTree(evidence.payload(id));
            var n = data.path("resource");
            String kind = text(data, "kind"), event = text(data, "eventId");
            if (kind.equals("transfers")) {
                if (!(transfers instanceof WechatTransferProvider real))
                    throw unavailable("WECHAT_TRANSFER_UNAVAILABLE");
                String bill = text(n, "out_bill_no");
                var ids =
                        db.queryForList(
                                "select id from withdrawal_request where channel='WECHAT_TRANSFER'"
                                        + " and out_bill_no=?",
                                Long.class,
                                bill);
                if (ids.size() != 1) throw failure("WECHAT_UNKNOWN_BUSINESS_NUMBER");
                var i = withdrawals.channelIntent(ids.getFirst());
                withdrawals.observe(
                        i.id(), real.observation(i, n, "CALLBACK", true, event), "CALLBACK");
                return;
            }
            if (!(payments instanceof WechatPaymentProvider real))
                throw unavailable("WECHAT_PAYMENT_UNAVAILABLE");
            boolean refund = kind.equals("refunds");
            String bill = text(n, refund ? "out_refund_no" : "out_trade_no");
            var ids =
                    db.queryForList(
                            refund
                                    ? "select id from refund_transaction where channel='WECHAT_PAY'"
                                            + " and out_refund_no=?"
                                    : "select id from payment_transaction where"
                                            + " channel='WECHAT_PAY' and out_trade_no=?",
                            Long.class,
                            bill);
            if (ids.size() != 1) throw failure("WECHAT_UNKNOWN_BUSINESS_NUMBER");
            var i = refund ? tx.refund(ids.getFirst()) : tx.payment(ids.getFirst());
            var r = refund ? real.refundResult(i, n, true) : real.payment(i, n, true);
            var e =
                    new PaymentProvider.VerifiedEvent(
                            "WECHAT_PAY",
                            event,
                            refund ? "REFUND" : "PAYMENT",
                            i.id(),
                            i.orderId(),
                            i.businessNo(),
                            r.channelId(),
                            r.amountFen(),
                            r.currency(),
                            r.appId(),
                            r.merchantId(),
                            r.state(),
                            true,
                            n.toString());
            long inboxId = inbox.record(e);
            String error = inbox.process(inboxId, e);
            if (error != null) throw failure(error);
            if (r.state() != PaymentProvider.State.SUCCEEDED) {
                if (refund) refunds.apply(i, r);
                else tx.applyResult(i, r);
            }
        } catch (com.wechat.jxsc.common.ApiException e) {
            evidence.issue(id, e.getCode());
            throw e;
        } catch (Exception e) {
            evidence.issue(id, "WECHAT_CALLBACK_UNRESOLVED");
            throw failure("WECHAT_CALLBACK_UNRESOLVED");
        }
    }
}
