package com.wechat.jxsc.payment.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.payment.PaymentProvider;

import java.time.Clock;
import java.util.*;

public final class WechatPaymentProvider implements PaymentProvider {
    private final WechatHttp http;
    private final WechatSettings settings;
    private final Clock clock;

    public WechatPaymentProvider(WechatHttp http, WechatSettings settings, Clock clock) {
        this.http = http;
        this.settings = settings;
        this.clock = clock;
    }

    public String channel() {
        return "WECHAT_PAY";
    }

    private void identity(Intent i, boolean refund) {
        equal(i.channel(), channel());
        equal(i.appId(), settings.appId());
        equal(i.merchantId(), settings.merchantId());
        equal(i.currency(), "CNY");
        if (i.amountFen() <= 0
                || !i.businessNo()
                        .matches(refund ? "[A-Za-z0-9_\\-|*@]{1,64}" : "[A-Za-z0-9_\\-|*]{6,32}"))
            throw failure("WECHAT_INVALID_INTENT");
    }

    public Result prepay(Intent i) {
        identity(i, false);
        settings.validatePath(i.productPath());
        if (i.payer() == null || i.payer().isBlank() || i.description() == null)
            throw unavailable("WECHAT_PAYER_NOT_BOUND");
        var body = new LinkedHashMap<String, Object>();
        body.put("appid", i.appId());
        body.put("mchid", i.merchantId());
        body.put("out_trade_no", i.businessNo());
        body.put("description", i.description());
        body.put("notify_url", settings.notifyUrl("payments"));
        body.put("amount", Map.of("total", i.amountFen(), "currency", i.currency()));
        body.put("payer", Map.of("openid", i.payer()));
        var response = http.json("POST", "/v3/pay/transactions/jsapi", body);
        String prepay = text(response, "prepay_id"),
                time = "" + clock.instant().getEpochSecond(),
                nonce = UUID.randomUUID().toString().replace("-", ""),
                pack = "prepay_id=" + prepay;
        return result(
                i,
                State.PROCESSING,
                null,
                Map.of(
                        "appId",
                        i.appId(),
                        "timeStamp",
                        time,
                        "nonceStr",
                        nonce,
                        "package",
                        pack,
                        "signType",
                        "RSA",
                        "paySign",
                        http.crypto()
                                .sign(
                                        i.appId() + "\n" + time + "\n" + nonce + "\n" + pack
                                                + "\n")));
    }

    public Result query(Intent i) {
        identity(i, false);
        return payment(
                i,
                http.json(
                        "GET",
                        "/v3/pay/transactions/out-trade-no/"
                                + path(i.businessNo())
                                + "?mchid="
                                + i.merchantId(),
                        null));
    }

    public Result close(Intent i) {
        identity(i, false);
        http.json(
                "POST",
                "/v3/pay/transactions/out-trade-no/" + path(i.businessNo()) + "/close",
                Map.of("mchid", i.merchantId()));
        return query(i);
    }

    public Result refund(Intent i) {
        identity(i, true);
        if (i.originalAmountFen() == null || i.originalBusinessNo() == null)
            throw failure("WECHAT_INVALID_INTENT");
        var response =
                http.json(
                        "POST",
                        "/v3/refund/domestic/refunds",
                        Map.of(
                                "out_trade_no",
                                i.originalBusinessNo(),
                                "out_refund_no",
                                i.businessNo(),
                                "notify_url",
                                settings.notifyUrl("refunds"),
                                "amount",
                                Map.of(
                                        "refund",
                                        i.amountFen(),
                                        "total",
                                        i.originalAmountFen(),
                                        "currency",
                                        i.currency())));
        return refundResult(i, response, false);
    }

    public Result queryRefund(Intent i) {
        identity(i, true);
        return refundResult(
                i,
                http.json("GET", "/v3/refund/domestic/refunds/" + path(i.businessNo()), null),
                false);
    }

    public Result payment(Intent i, JsonNode n) {
        return payment(i, n, false);
    }

    public Result payment(Intent i, JsonNode n, boolean callback) {
        identity(i, false);
        equal(text(n, "appid"), i.appId());
        equal(text(n, "mchid"), i.merchantId());
        equal(text(n, "out_trade_no"), i.businessNo());
        String rawState = text(n, "trade_state");
        if (callback) {
            equal(text(n.path("amount"), "currency"), i.currency());
            equal(text(n.path("payer"), "openid"), i.payer());
        }
        if (n.path("amount").has("total") || rawState.equals("SUCCESS"))
            equal(amount(n.path("amount"), "total"), i.amountFen());
        if (n.path("amount").has("currency"))
            equal(text(n.path("amount"), "currency"), i.currency());
        if (n.path("payer").has("openid") && i.payer() != null)
            equal(text(n.path("payer"), "openid"), i.payer());
        State state =
                switch (rawState) {
                    case "SUCCESS" -> State.SUCCEEDED;
                    case "CLOSED" -> State.CLOSED;
                    case "NOTPAY", "USERPAYING" -> State.PROCESSING;
                    default -> State.UNKNOWN;
                };
        return result(
                i,
                state,
                state == State.SUCCEEDED
                        ? text(n, "transaction_id")
                        : n.path("transaction_id").asText(null),
                Map.of());
    }

    public Result refundResult(Intent i, JsonNode n, boolean callback) {
        identity(i, true);
        equal(text(n, "out_refund_no"), i.businessNo());
        equal(text(n, "out_trade_no"), i.originalBusinessNo());
        equal(text(n, "transaction_id"), i.originalChannelId());
        equal(amount(n.path("amount"), "refund"), i.amountFen());
        equal(amount(n.path("amount"), "total"), i.originalAmountFen());
        if (callback) equal(text(n, "mchid"), i.merchantId());
        if (n.has("appid")) equal(text(n, "appid"), i.appId());
        if (n.path("amount").has("currency"))
            equal(text(n.path("amount"), "currency"), i.currency());
        else if (!callback) throw failure("WECHAT_INVALID_RESPONSE");
        String raw = text(n, callback ? "refund_status" : "status");
        State state =
                switch (raw) {
                    case "SUCCESS" -> State.SUCCEEDED;
                    case "CLOSED" -> State.CLOSED;
                    case "PROCESSING" -> State.PROCESSING;
                    default -> State.UNKNOWN;
                };
        return result(
                i,
                state,
                text(n, "refund_id"),
                "ABNORMAL".equals(raw) ? Map.of("channelException", "REFUND_ABNORMAL") : Map.of());
    }

    private Result result(Intent i, State state, String channelId, Map<String, String> params) {
        return new Result(
                state, channelId, i.amountFen(), i.currency(), i.appId(), i.merchantId(), params);
    }
}
