package com.wechat.jxsc.payment.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;

@Component
public final class WechatStatements {
    public record Row(
            String resource,
            String businessNo,
            String channelNo,
            long amountFen,
            String state,
            String flow) {}

    public record Statement(
            LocalDate date,
            String type,
            String sha1,
            String source,
            List<Row> rows,
            String availability,
            String signedEvidence) {
        public Statement(LocalDate date, String type, String sha1, String source, List<Row> rows) {
            this(date, type, sha1, source, rows, "AVAILABLE", null);
        }
    }

    private final WechatHttp http;
    private final WechatSettings settings;
    private final Clock clock;

    public WechatStatements(WechatHttp http, WechatSettings settings, Clock clock) {
        this.http = http;
        this.settings = settings;
        this.clock = clock;
    }

    public Statement download(LocalDate date, String type) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        if (!date.isBefore(today) || date.isBefore(today.minusMonths(3)))
            throw failure("WECHAT_STATEMENT_DATE_INVALID");
        if (!Set.of("TRADE", "FUND_BASIC", "FUND_OPERATION").contains(type))
            throw failure("WECHAT_STATEMENT_TYPE_INVALID");
        String path =
                type.equals("TRADE")
                        ? "/v3/bill/tradebill?bill_date=" + date + "&bill_type=ALL"
                        : "/v3/bill/fundflowbill?bill_date="
                                + date
                                + "&account_type="
                                + type.substring(5);
        var response = http.signedJson("GET", path, null);
        var applied = response.body();
        if (response.status() == 400
                && Set.of("NO_STATEMENT_EXIST", "STATEMENT_CREATING")
                        .contains(applied.path("code").asText()))
            return new Statement(
                    date,
                    type,
                    null,
                    null,
                    List.of(),
                    applied.path("code").asText().equals("NO_STATEMENT_EXIST")
                            ? "ABSENT"
                            : "CREATING",
                    response.signedEvidence());
        if (response.status() != 200) throw failure("WECHAT_STATEMENT_UNAVAILABLE");
        equal(text(applied, "hash_type"), "SHA1");
        String hash = text(applied, "hash_value");
        byte[] data = http.download(text(applied, "download_url"), 16 * 1024 * 1024);
        var parsed = parse(date, type, hash, data);
        return new Statement(
                date,
                type,
                parsed.sha1(),
                parsed.source(),
                parsed.rows(),
                "AVAILABLE",
                response.signedEvidence());
    }

    public Statement parse(LocalDate date, String type, String hash, byte[] data) {
        try {
            if (data.length > 16 * 1024 * 1024 || !hash.matches("[a-fA-F0-9]{40}"))
                throw failure("WECHAT_STATEMENT_INVALID");
            String actual =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
            if (!MessageDigest.isEqual(bytes(actual), bytes(hash.toLowerCase(Locale.ROOT))))
                throw failure("WECHAT_STATEMENT_HASH_MISMATCH");
            // Uncompressed downloads are explicitly requested; no attacker-controlled
            // decompression.
            String source =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data))
                            .toString();
            String[] lines = source.replace("\uFEFF", "").split("\\r?\\n");
            if (lines.length < 3 || lines.length > 100003)
                throw failure("WECHAT_STATEMENT_INVALID");
            String[] headers = fields(lines[0]);
            List<Row> rows = new ArrayList<>();
            boolean summary = false;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                String[] values = fields(lines[i]);
                if (values[0].equals("总交易单数") || values[0].equals("资金流水总笔数")) {
                    if (i + 1 >= lines.length
                            || Long.parseLong(fields(lines[i + 1])[0]) != rows.size())
                        throw failure("WECHAT_STATEMENT_COUNT_MISMATCH");
                    for (int tail = i + 2; tail < lines.length; tail++)
                        if (!lines[tail].isBlank()) throw failure("WECHAT_STATEMENT_INVALID");
                    summary = true;
                    break;
                }
                if (values.length != headers.length) throw failure("WECHAT_STATEMENT_INVALID");
                Map<String, String> row = new HashMap<>();
                for (int k = 0; k < headers.length; k++) row.put(headers[k], values[k]);
                if (type.equals("TRADE")) {
                    equal(row.get("公众账号ID"), settings.appId());
                    equal(row.get("商户号"), settings.merchantId());
                    equal(row.get("货币种类"), "CNY");
                    requireDay(row.get("交易时间"), date);
                    String state = required(row, "交易状态");
                    boolean refund = state.equals("REFUND");
                    rows.add(
                            new Row(
                                    refund ? "REFUND" : "PAYMENT",
                                    required(row, refund ? "商户退款单号" : "商户订单号"),
                                    required(row, refund ? "微信退款单号" : "微信订单号"),
                                    fen(required(row, refund ? "申请退款金额" : "订单金额")),
                                    refund ? required(row, "退款状态") : state,
                                    ""));
                } else {
                    requireDay(row.get("记账时间"), date);
                    rows.add(
                            new Row(
                                    "FUND",
                                    row.getOrDefault("业务凭证号", ""),
                                    required(row, "微信支付业务单号"),
                                    fen(required(row, "收支金额(元)")),
                                    "FUND_FLOW",
                                    required(row, "收支类型")));
                }
            }
            if (!summary) throw failure("WECHAT_STATEMENT_INVALID");
            return new Statement(date, type, actual, source, List.copyOf(rows));
        } catch (com.wechat.jxsc.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw failure("WECHAT_STATEMENT_INVALID");
        }
    }

    private static String[] fields(String line) {
        return Arrays.stream(line.split(",", -1))
                .map(v -> v.startsWith("`") ? v.substring(1) : v)
                .toArray(String[]::new);
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) throw failure("WECHAT_STATEMENT_INVALID");
        return value;
    }

    private static void requireDay(String time, LocalDate date) {
        if (time == null
                || !java.time.LocalDateTime.parse(
                                time,
                                java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                        .withResolverStyle(java.time.format.ResolverStyle.STRICT))
                        .toLocalDate()
                        .equals(date)) throw failure("WECHAT_STATEMENT_DATE_MISMATCH");
    }

    private static long fen(String value) {
        long amount = new BigDecimal(value).movePointRight(2).longValueExact();
        if (amount < 0) throw failure("WECHAT_STATEMENT_INVALID");
        return amount;
    }
}
