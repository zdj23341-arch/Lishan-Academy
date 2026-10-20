package com.wechat.jxsc.distribution.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import com.fasterxml.jackson.databind.*;
import com.wechat.jxsc.distribution.TransferProvider;
import com.wechat.jxsc.payment.wechat.*;

import java.util.*;

public final class WechatTransferProvider implements TransferProvider {
    private final WechatHttp http;
    private final WechatSettings settings;
    private final ObjectMapper json;

    public WechatTransferProvider(WechatHttp http, WechatSettings settings, ObjectMapper json) {
        this.http = http;
        this.settings = settings;
        this.json = json;
    }

    public boolean ready() {
        try {
            createConfiguration();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean recoveryReady() {
        return http.ready() && !appId().isBlank() && !merchantId().isBlank();
    }

    private JsonNode createConfiguration() {
        if (!http.ready()
                || !settings.enabled("transfer.enabled")
                || settings.get("transfer.scene-id").isBlank()
                || settings.get("transfer.approval-reference").isBlank()
                || settings.get("transfer.remark").isBlank()
                || maximumFen() <= 0) throw unavailable("WITHDRAWAL_NOT_READY");
        settings.notifyUrl("transfers");
        http.requireEncryptionKey(settings.get("pay.encryption-key-id"));
        try {
            JsonNode reports = json.readTree(settings.get("transfer.scene-reports"));
            if (reports == null || !reports.isArray() || reports.isEmpty())
                throw new IllegalArgumentException();
            for (var r : reports)
                if (text(r, "info_type").length() > 15 || text(r, "info_content").length() > 32)
                    throw new IllegalArgumentException();
            return reports;
        } catch (Exception e) {
            throw unavailable("WECHAT_SCENE_REPORTS_REQUIRED");
        }
    }

    @Override
    public void preflight(Intent i) {
        identity(i);
        createConfiguration();
        if (i.amountFen() > maximumFen()) throw unavailable("WECHAT_TRANSFER_LIMIT");
        if (i.amountFen() >= 200000 && (i.recipientName() == null || i.recipientName().isBlank()))
            throw unavailable("WECHAT_RECIPIENT_NAME_REQUIRED");
        if (i.recipientName() != null)
            http.crypto().encryptName(i.recipientName(), settings.get("pay.encryption-key-id"));
    }

    public String channel() {
        return "WECHAT_TRANSFER";
    }

    public String appId() {
        return settings.appId();
    }

    public String merchantId() {
        return settings.merchantId();
    }

    public long maximumFen() {
        return settings.transferMax();
    }

    private void identity(Intent i) {
        if (!recoveryReady()) throw unavailable("WITHDRAWAL_NOT_READY");
        equal(i.channel(), channel());
        equal(i.appId(), appId());
        equal(i.merchantId(), merchantId());
        if (!i.outBillNo().matches("[A-Za-z0-9]{1,32}")
                || i.recipient() == null
                || i.recipient().isBlank()
                || i.amountFen() <= 0) throw failure("WECHAT_INVALID_INTENT");
    }

    public Observation send(Intent i) {
        preflight(i);
        if (i.amountFen() > maximumFen()) throw unavailable("WECHAT_TRANSFER_LIMIT");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", i.appId());
        body.put("out_bill_no", i.outBillNo());
        body.put("openid", i.recipient());
        body.put("transfer_amount", i.amountFen());
        body.put("transfer_scene_id", settings.get("transfer.scene-id"));
        body.put("transfer_remark", settings.get("transfer.remark"));
        body.put("notify_url", settings.notifyUrl("transfers"));
        try {
            JsonNode reports = json.readTree(settings.get("transfer.scene-reports"));
            if (!reports.isArray() || reports.isEmpty()) throw new IllegalArgumentException();
            for (var r : reports)
                if (text(r, "info_type").length() > 15 || text(r, "info_content").length() > 32)
                    throw new IllegalArgumentException();
            body.put("transfer_scene_report_infos", reports);
        } catch (Exception e) {
            throw unavailable("WECHAT_SCENE_REPORTS_REQUIRED");
        }
        String serial = settings.get("pay.encryption-key-id");
        if (i.amountFen() >= 200000 && (i.recipientName() == null || i.recipientName().isBlank()))
            throw unavailable("WECHAT_RECIPIENT_NAME_REQUIRED");
        if (i.recipientName() != null)
            body.put("user_name", http.crypto().encryptName(i.recipientName(), serial));
        var n =
                i.recipientName() == null
                        ? http.json("POST", "/v3/fund-app/mch-transfer/transfer-bills", body)
                        : http.json(
                                "POST", "/v3/fund-app/mch-transfer/transfer-bills", body, serial);
        return observation(i, n, "SEND", false, null);
    }

    public Observation query(Intent i) {
        identity(i);
        return observation(
                i,
                http.json(
                        "GET",
                        "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + i.outBillNo(),
                        null),
                "QUERY",
                true,
                null);
    }

    public Observation cancel(Intent i) {
        identity(i);
        var response =
                http.json(
                        "POST",
                        "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/"
                                + i.outBillNo()
                                + "/cancel",
                        Map.of());
        equal(text(response, "out_bill_no"), i.outBillNo());
        return query(i);
    }

    public Observation observation(
            Intent i, JsonNode n, String source, boolean complete, String eventId) {
        identity(i);
        equal(text(n, "out_bill_no"), i.outBillNo());
        if (complete) {
            if (!source.equals("CALLBACK") || n.has("appid")) equal(text(n, "appid"), i.appId());
            equal(text(n, "mch_id"), i.merchantId());
            equal(amount(n, "transfer_amount"), i.amountFen());
        }
        if (source.equals("CALLBACK") || n.has("openid")) equal(text(n, "openid"), i.recipient());
        String raw = n.toString(), state = text(n, "state");
        return new Observation(
                eventId == null ? UUID.randomUUID().toString() : eventId,
                i.outBillNo(),
                i.amountFen(),
                i.appId(),
                i.merchantId(),
                i.recipient(),
                channel(),
                text(n, "transfer_bill_no"),
                state,
                "SEND".equals(source) && "WAIT_USER_CONFIRM".equals(state)
                        ? n.path("package_info").asText(null)
                        : null,
                true,
                source + ":" + raw,
                state.equals("FAIL") ? safeFailure(n.path("fail_reason").asText()) : null);
    }

    private String safeFailure(String reason) {
        return Set.of("PAYEE_ACCOUNT_ABNORMAL").contains(reason)
                ? reason
                : "WECHAT_TRANSFER_FAILED";
    }
}
