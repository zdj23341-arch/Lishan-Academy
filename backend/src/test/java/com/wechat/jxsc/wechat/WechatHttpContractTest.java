package com.wechat.jxsc.wechat;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.wechat.jxsc.auth.wechat.*;
import com.wechat.jxsc.distribution.*;
import com.wechat.jxsc.distribution.wechat.*;
import com.wechat.jxsc.payment.*;
import com.wechat.jxsc.payment.wechat.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.*;
import javax.crypto.spec.*;

class WechatHttpContractTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"), ZoneOffset.UTC);
    static final String API = "01234567890123456789012345678901";
    KeyPair merchant, platform, rotated;
    HttpServer server;
    WechatHttp http;
    WechatCrypto crypto;
    WechatSettings settings;
    String response = "{}", serial = "PUB1", responseTime = "" + CLOCK.instant().getEpochSecond();
    int status = 200;
    boolean corrupt = false;
    byte[] download;
    boolean stallBody;
    List<String> paths = new ArrayList<>();
    List<String> requestedSerials = new ArrayList<>();
    List<JsonNode> bodies = new ArrayList<>();
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        merchant = gen.generateKeyPair();
        platform = gen.generateKeyPair();
        rotated = gen.generateKeyPair();
        crypto =
                new WechatCrypto(
                        "1900001",
                        "CERT1",
                        merchant.getPrivate(),
                        Map.of("PUB1", platform.getPublic(), "PUB2", rotated.getPublic()),
                        API.getBytes(StandardCharsets.UTF_8),
                        CLOCK);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        http = new WechatHttp(origin(), crypto, JSON);
        settings =
                new WechatSettings(
                        new MockEnvironment()
                                .withProperty("jxsc.wechat.app-id", "wxapp")
                                .withProperty("jxsc.wechat.merchant-id", "1900001")
                                .withProperty("jxsc.wechat.pay.book-jsapi-approved", "true")
                                .withProperty(
                                        "jxsc.wechat.pay.notify-base-url",
                                        "https://merchant.example")
                                .withProperty("jxsc.wechat.transfer.enabled", "true")
                                .withProperty("jxsc.wechat.transfer.scene-id", "APPROVED_SCENE")
                                .withProperty(
                                        "jxsc.wechat.transfer.scene-reports",
                                        "[{\"info_type\":\"岗位类型\",\"info_content\":\"佣金报酬\"}]")
                                .withProperty(
                                        "jxsc.wechat.transfer.approval-reference",
                                        "test-fixture-only")
                                .withProperty("jxsc.wechat.transfer.remark", "佣金报酬")
                                .withProperty("jxsc.wechat.pay.encryption-key-id", "PUB1")
                                .withProperty("jxsc.wechat.transfer.max-fen", "500000"));
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respond(HttpExchange e) {
        try {
            byte[] body = e.getRequestBody().readAllBytes();
            paths.add(e.getRequestMethod() + " " + e.getRequestURI());
            requestedSerials.add(e.getRequestHeaders().getFirst("Wechatpay-Serial"));
            if (!e.getRequestURI().getPath().equals("/sns/jscode2session")) {
                String auth = e.getRequestHeaders().getFirst("Authorization");
                var values = new HashMap<String, String>();
                var match = java.util.regex.Pattern.compile("(\\w+)=\"([^\"]+)\"").matcher(auth);
                while (match.find()) values.put(match.group(1), match.group(2));
                assertThat(values.get("mchid")).isEqualTo("1900001");
                assertThat(values.get("serial_no")).isEqualTo("CERT1");
                String canonical =
                        e.getRequestMethod()
                                + "\n"
                                + e.getRequestURI()
                                + "\n"
                                + values.get("timestamp")
                                + "\n"
                                + values.get("nonce_str")
                                + "\n"
                                + new String(body, StandardCharsets.UTF_8)
                                + "\n";
                assertThat(
                                verify(
                                        merchant.getPublic(),
                                        canonical.getBytes(StandardCharsets.UTF_8),
                                        values.get("signature")))
                        .isTrue();
                if (body.length > 0) bodies.add(JSON.readTree(body));
            }
            byte[] out = download != null ? download : response.getBytes(StandardCharsets.UTF_8);
            if (download == null)
                signedHeaders(out).forEach((k, v) -> e.getResponseHeaders().add(k, v));
            e.sendResponseHeaders(status, status == 204 ? -1 : out.length);
            if (stallBody) Thread.sleep(17000);
            if (status != 204) e.getResponseBody().write(out);
        } catch (Throwable t) {
            if (!stallBody || !(t instanceof java.io.IOException)) serverFailure.set(t);
        } finally {
            e.close();
        }
    }

    Map<String, String> signedHeaders(byte[] body) throws Exception {
        byte[] content =
                (responseTime
                                + "\nresponse-nonce\n"
                                + new String(body, StandardCharsets.UTF_8)
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8);
        String signature =
                sign("PUB2".equals(serial) ? rotated.getPrivate() : platform.getPrivate(), content);
        if (corrupt) signature = sign(merchant.getPrivate(), content);
        return Map.of(
                "Wechatpay-Serial",
                serial,
                "Wechatpay-Timestamp",
                responseTime,
                "Wechatpay-Nonce",
                "response-nonce",
                "Wechatpay-Signature",
                signature);
    }

    static String sign(PrivateKey key, byte[] body) throws Exception {
        var s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(body);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    static boolean verify(PublicKey key, byte[] body, String signed) throws Exception {
        var s = Signature.getInstance("SHA256withRSA");
        s.initVerify(key);
        s.update(body);
        return s.verify(Base64.getDecoder().decode(signed));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        assertThat(serverFailure.get()).isNull();
    }

    @Test
    void responseBodyHasAnOverallDeadlineAfterHeaders() {
        stallBody = true;
        long start = System.nanoTime();
        assertThatThrownBy(() -> http.json("GET", "/v3/stalled", null))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofMillis(16500));
    }

    @Test
    void oversizedResponseIsRejectedBeforeParsingOrVerification() {
        response = "x".repeat(1024 * 1024 + 1);
        assertThatThrownBy(() -> http.json("GET", "/v3/oversized", null))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    PaymentProvider.Intent payment() {
        return new PaymentProvider.Intent(
                1,
                2,
                "A123456789",
                "WECHAT_PAY",
                100,
                "CNY",
                "wxapp",
                "1900001",
                null,
                null,
                null,
                "openid-private",
                "实体书",
                "BOOK_JSAPI");
    }

    PaymentProvider.Intent refund() {
        return new PaymentProvider.Intent(
                3,
                2,
                "R123456789",
                "WECHAT_PAY",
                40,
                "CNY",
                "wxapp",
                "1900001",
                "A123456789",
                "WX123",
                100L);
    }

    TransferProvider.Intent transfer() {
        return new TransferProvider.Intent(
                4, "T123456789", 100, "wxapp", "1900001", "openid-private", "WECHAT_TRANSFER");
    }

    WechatPaymentProvider pay() {
        return new WechatPaymentProvider(http, settings, CLOCK);
    }

    WechatTransferProvider transferProvider() {
        return new WechatTransferProvider(http, settings, JSON);
    }

    String paymentResponse(String state) {
        return "{\"appid\":\"wxapp\",\"mchid\":\"1900001\",\"out_trade_no\":\"A123456789\",\"transaction_id\":\"WX123\",\"trade_state\":\""
                + state
                + "\",\"amount\":{\"total\":100,\"currency\":\"CNY\"},\"payer\":{\"openid\":\"openid-private\"}}";
    }

    @Test
    void signsRealRequestAndPrepayParametersWithoutTreatingHttp200AsPaid() throws Exception {
        response = "{\"prepay_id\":\"private-prepay\"}";
        var r = pay().prepay(payment());
        assertThat(r.state()).isEqualTo(PaymentProvider.State.PROCESSING);
        assertThat(r.channelId()).isNull();
        assertThat(bodies.getFirst().path("payer").path("openid").asText())
                .isEqualTo("openid-private");
        var p = r.params();
        assertThat(
                        verify(
                                merchant.getPublic(),
                                ("wxapp\n"
                                                + p.get("timeStamp")
                                                + "\n"
                                                + p.get("nonceStr")
                                                + "\n"
                                                + p.get("package")
                                                + "\n")
                                        .getBytes(StandardCharsets.UTF_8),
                                p.get("paySign")))
                .isTrue();
    }

    @Test
    void queryAuthenticatesOriginalNumberAmountCurrencyAndPayer() {
        response = paymentResponse("SUCCESS");
        assertThat(pay().query(payment()).state()).isEqualTo(PaymentProvider.State.SUCCEEDED);
        response = response.replace("\"total\":100", "\"total\":101");
        assertThatThrownBy(() -> pay().query(payment())).hasMessageNotContaining("openid-private");
        assertThat(paths)
                .allMatch(
                        p ->
                                p.equals(
                                        "GET /v3/pay/transactions/out-trade-no/A123456789?mchid=1900001"));
    }

    @Test
    void unknownStateAndSignedNotFoundNeverBecomeFinancialFailure() {
        response = paymentResponse("NEW_CHANNEL_STATE");
        assertThat(pay().query(payment()).state()).isEqualTo(PaymentProvider.State.UNKNOWN);
        status = 404;
        response = "{\"code\":\"NOT_FOUND\",\"message\":\"sensitive\"}";
        assertThatThrownBy(() -> pay().query(payment())).hasMessageNotContaining("sensitive");
    }

    @Test
    void sparseUnpaidQueryAssociatesOptionalMoneyWithFrozenOriginalIntent() {
        response =
                "{\"appid\":\"wxapp\",\"mchid\":\"1900001\",\"out_trade_no\":\"A123456789\",\"trade_state\":\"NOTPAY\",\"trade_state_desc\":\"未支付\"}";
        assertThatCode(
                        () ->
                                assertThat(pay().query(payment()).state())
                                        .isEqualTo(PaymentProvider.State.PROCESSING))
                .doesNotThrowAnyException();
        response = response.replace("NOTPAY", "SUCCESS");
        assertThatThrownBy(() -> pay().query(payment()))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    @Test
    void rejectsUntrustedSignatureAndExpiredTimestampAllowsPinnedRotation() {
        response = paymentResponse("SUCCESS");
        serial = "PUB2";
        assertThat(pay().query(payment()).state()).isEqualTo(PaymentProvider.State.SUCCEEDED);
        serial = "UNTRUSTED";
        assertThatThrownBy(() -> pay().query(payment())).hasMessageNotContaining("PUB");
        serial = "PUB1";
        corrupt = true;
        assertThatThrownBy(() -> pay().query(payment()))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        corrupt = false;
        responseTime = "" + (CLOCK.instant().getEpochSecond() - 301);
        assertThatThrownBy(() -> pay().query(payment()))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    @Test
    void abnormalRefundStaysUnresolvedWithActionableReason() {
        response =
                "{\"out_refund_no\":\"R123456789\",\"out_trade_no\":\"A123456789\",\"transaction_id\":\"WX123\",\"refund_id\":\"WXR1\",\"status\":\"ABNORMAL\",\"amount\":{\"refund\":40,\"total\":100,\"currency\":\"CNY\"}}";
        var r = pay().refund(refund());
        assertThat(r.state()).isEqualTo(PaymentProvider.State.UNKNOWN);
        assertThat(r.params()).containsEntry("channelException", "REFUND_ABNORMAL");
        assertThat(bodies.getFirst().path("out_trade_no").asText()).isEqualTo("A123456789");
    }

    @Test
    void sparseTransferCreatePreservesReceiptWhileQueryDoesNotInventIt() {
        response =
                "{\"out_bill_no\":\"T123456789\",\"transfer_bill_no\":\"WXTRANS\",\"state\":\"WAIT_USER_CONFIRM\",\"create_time\":\"2026-10-07T12:00:00+08:00\",\"package_info\":\"private-package\"}";
        var created = transferProvider().send(transfer());
        assertThat(created.state()).isEqualTo("WAIT_USER_CONFIRM");
        assertThat(created.packageInfo()).isEqualTo("private-package");
        assertThat(created.authenticatedPayload()).doesNotContain("openid");
        response =
                "{\"mch_id\":\"1900001\",\"appid\":\"wxapp\",\"out_bill_no\":\"T123456789\",\"transfer_bill_no\":\"WXTRANS\",\"transfer_amount\":100,\"state\":\"WAIT_USER_CONFIRM\"}";
        var queried = transferProvider().query(transfer());
        assertThat(queried.packageInfo()).isNull();
        assertThat(queried.recipient()).isEqualTo("openid-private");
        assertThat(queried.authenticatedPayload()).doesNotContain("openid");
    }

    @Test
    void transferQueryRejectsWrongOptionalRecipientAndAmount() {
        response =
                "{\"mch_id\":\"1900001\",\"appid\":\"wxapp\",\"out_bill_no\":\"T123456789\",\"transfer_bill_no\":\"WXTRANS\",\"transfer_amount\":100,\"state\":\"SUCCESS\",\"openid\":\"someone-else\"}";
        assertThatThrownBy(() -> transferProvider().query(transfer()))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        response =
                response.replace("someone-else", "openid-private")
                        .replace("\"transfer_amount\":100", "\"transfer_amount\":999");
        assertThatThrownBy(() -> transferProvider().query(transfer()))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    @Test
    void highValueTransferRequiresAndEncryptsRealName() throws Exception {
        var i =
                new TransferProvider.Intent(
                        4,
                        "T123456789",
                        200000,
                        "wxapp",
                        "1900001",
                        "openid-private",
                        "WECHAT_TRANSFER");
        assertThatThrownBy(() -> transferProvider().send(i))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(paths).isEmpty();
        response =
                "{\"out_bill_no\":\"T123456789\",\"transfer_bill_no\":\"WXTRANS\",\"state\":\"ACCEPTED\"}";
        transferProvider()
                .send(
                        new TransferProvider.Intent(
                                4,
                                "T123456789",
                                200000,
                                "wxapp",
                                "1900001",
                                "openid-private",
                                "WECHAT_TRANSFER",
                                "张三"));
        var decrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        decrypt.init(Cipher.DECRYPT_MODE, platform.getPrivate());
        assertThat(
                        new String(
                                decrypt.doFinal(
                                        Base64.getDecoder()
                                                .decode(
                                                        bodies.getFirst()
                                                                .path("user_name")
                                                                .asText())),
                                StandardCharsets.UTF_8))
                .isEqualTo("张三");
    }

    @Test
    void derivedFailureReasonPreservesExistingObservationWireHash() {
        String raw = "CALLBACK:{\"fail_reason\":\"PAYEE_ACCOUNT_ABNORMAL\"}";
        var legacy =
                new TransferProvider.Observation(
                        "event",
                        "T123456789",
                        100,
                        "wxapp",
                        "1900001",
                        "openid-private",
                        "WECHAT_TRANSFER",
                        "WXTRANS",
                        "FAIL",
                        null,
                        true,
                        raw);
        var current =
                new TransferProvider.Observation(
                        "event",
                        "T123456789",
                        100,
                        "wxapp",
                        "1900001",
                        "openid-private",
                        "WECHAT_TRANSFER",
                        "WXTRANS",
                        "FAIL",
                        null,
                        true,
                        raw,
                        "PAYEE_ACCOUNT_ABNORMAL");
        JsonNode currentJson = JSON.valueToTree(current), legacyJson = JSON.valueToTree(legacy);
        assertThat(currentJson).isEqualTo(legacyJson);
        assertThat(JSON.valueToTree(current).has("safeFailureCode")).isFalse();
    }

    @Test
    void authenticatesAesGcmTagNonceAndAssociatedData() throws Exception {
        var c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, "123456789012".getBytes(StandardCharsets.UTF_8)));
        c.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        var resource =
                JSON.createObjectNode()
                        .put("algorithm", "AEAD_AES_256_GCM")
                        .put("nonce", "123456789012")
                        .put("associated_data", "transaction")
                        .put(
                                "ciphertext",
                                Base64.getEncoder()
                                        .encodeToString(
                                                c.doFinal(
                                                        paymentResponse("SUCCESS")
                                                                .getBytes(
                                                                        StandardCharsets.UTF_8))));
        byte[] body = JSON.writeValueAsBytes(Map.of("resource", resource));
        crypto.verify(body, signedHeaders(body));
        assertThat(new String(crypto.decrypt(resource), StandardCharsets.UTF_8))
                .contains("A123456789");
        resource.put("associated_data", "other");
        assertThatThrownBy(() -> crypto.decrypt(resource))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThatThrownBy(
                        () ->
                                crypto.verify(
                                        "{}".getBytes(StandardCharsets.UTF_8), signedHeaders(body)))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    @Test
    void loginUsesConfiguredAppAndRejectsRiskMalformedAndInvalidCode() {
        var login = new WechatLoginClient(origin(), "wxapp", "private-secret", JSON);
        response =
                "{\"openid\":\"openid-private\",\"session_key\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"unionid\":\"union\"}";
        assertThat(login.exchange("one-use-code").appId()).isEqualTo("wxapp");
        assertThat(paths.getFirst()).contains("appid=wxapp", "grant_type=authorization_code");
        for (String bad :
                List.of(
                        "{}",
                        "{\"errcode\":40029}",
                        "{\"errcode\":45011}",
                        "{\"errcode\":40226}",
                        "not-json")) {
            response = bad;
            assertThatThrownBy(() -> login.exchange("one-use-code"))
                    .hasMessageNotContaining("private-secret")
                    .hasMessageNotContaining("one-use-code");
        }
    }

    @Test
    void digitalContentCannotBorrowBookCapability() {
        var i = payment();
        var digital =
                new PaymentProvider.Intent(
                        i.id(),
                        i.orderId(),
                        i.businessNo(),
                        i.channel(),
                        i.amountFen(),
                        i.currency(),
                        i.appId(),
                        i.merchantId(),
                        null,
                        null,
                        null,
                        i.payer(),
                        "课程",
                        "DIGITAL_JSAPI_VERIFIED");
        assertThatThrownBy(() -> pay().prepay(digital))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThat(paths).isEmpty();
    }

    @Test
    void responseSigningConfigurationRequiresExplicitModeAndTrustedActivePublicKey() {
        String active = "PUB_KEY_ID_00000000000000000000000000000001";
        var trust =
                new WechatCrypto(
                        "1900001",
                        "CERT1",
                        merchant.getPrivate(),
                        Map.of(active, platform.getPublic(), "ABC123", rotated.getPublic()),
                        API.getBytes(StandardCharsets.UTF_8),
                        CLOCK);
        var env = new MockEnvironment();
        var configured = new WechatSettings(env);
        assertThatThrownBy(() -> configured.responseSerial(trust))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        env.withProperty("jxsc.wechat.pay.response-signing-mode", "PUBLIC_KEY")
                .withProperty("jxsc.wechat.pay.response-key-id", active);
        assertThat(configured.responseSerial(trust)).isEqualTo(active);
        env.withProperty("jxsc.wechat.pay.response-key-id", "PUB_KEY_ID_UNKNOWN");
        assertThatThrownBy(() -> configured.responseSerial(trust))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        env.withProperty("jxsc.wechat.pay.response-signing-mode", "CERTIFICATE")
                .withProperty("jxsc.wechat.pay.response-key-id", "");
        assertThat(configured.responseSerial(trust)).isNull();
        env.withProperty("jxsc.wechat.pay.response-key-id", active);
        assertThatThrownBy(() -> configured.responseSerial(trust))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
    }

    @Test
    void publicKeyResponseModeNegotiatesEveryOperationAndRetainsTrustOverlap() {
        http = new WechatHttp(origin(), crypto, JSON, e -> {}, "PUB1");
        for (String path :
                List.of(
                        "/v3/pay/transactions/jsapi",
                        "/v3/pay/transactions/out-trade-no/A123456789?mchid=1900001",
                        "/v3/refund/domestic/refunds",
                        "/v3/bill/fundflowbill?bill_date=2026-10-06&account_type=BASIC")) {
            http.json(
                    path.contains("?") ? "GET" : "POST",
                    path,
                    path.contains("?") ? null : Map.of());
        }
        // The second trusted serial models a pinned certificate/public-key overlap response.
        serial = "PUB2";
        http.json("GET", "/v3/overlap", null);
        http.json(
                "POST",
                "/v3/fund-app/mch-transfer/transfer-bills",
                Map.of("user_name", crypto.encryptName("张三", "PUB1")),
                "PUB1");
        assertThat(requestedSerials).containsOnly("PUB1").hasSize(6);
        assertThatThrownBy(() -> http.json("POST", "/v3/incompatible", Map.of(), "PUB2"))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        assertThatThrownBy(() -> new WechatHttp(origin(), crypto, JSON, e -> {}, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statementApplyVerifiesSignatureThenDownloadsOnlyTrustedHashedPlainBytes()
            throws Exception {
        String source =
                "交易时间,公众账号ID,商户号,货币种类,商户订单号,微信订单号,交易状态,订单金额,商户退款单号,微信退款单号,申请退款金额,退款状态\n"
                    + "`2026-10-06"
                    + " 12:00:00,`wxapp,`1900001,`CNY,`A123456789,`WX123,`SUCCESS,`1.00,`0,`0,`0.00,`\n"
                    + "总交易单数\n"
                    + "`1\n";
        byte[] data = source.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        // The apply/download exchange is covered with the same real signed HTTP test server.
        try (var stub = new WechatTestServer()) {
            stub.responder =
                    r ->
                            r.path().startsWith("/v3/billdownload/")
                                    ? new WechatTestServer.Response(200, data, false)
                                    : stub.jsonResponse(
                                            200,
                                            Map.of(
                                                    "hash_type",
                                                    "SHA1",
                                                    "hash_value",
                                                    hash,
                                                    "download_url",
                                                    stub.origin()
                                                            + "/v3/billdownload/file?token=private"));
            var client = new WechatStatements(stub.transport(Clock.systemUTC()), settings, CLOCK);
            var statement = client.download(LocalDate.parse("2026-10-06"), "TRADE");
            assertThat(statement.rows().getFirst().amountFen()).isEqualTo(100);
            stub.responder =
                    r ->
                            stub.jsonResponse(
                                    200,
                                    Map.of(
                                            "hash_type",
                                            "SHA1",
                                            "hash_value",
                                            hash,
                                            "download_url",
                                            "https://evil.example/v3/billdownload/file?token=private"));
            assertThatThrownBy(() -> client.download(LocalDate.parse("2026-10-06"), "TRADE"))
                    .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
            assertThat(stub.failure).isNull();
        }
    }

    @Test
    void statementsRejectChangedHashAndProviderIdentityAndNonIntegralFen() throws Exception {
        var client = new WechatStatements(http, settings, CLOCK);
        String source =
                "交易时间,公众账号ID,商户号,货币种类,商户订单号,微信订单号,交易状态,订单金额\n"
                        + "`2026-10-06"
                        + " 12:00:00,`wrong-app,`1900001,`CNY,`A123456789,`WX123,`SUCCESS,`1.001\n"
                        + "总交易单数\n"
                        + "`1\n";
        for (String s : List.of(source, source.replace("wrong-app", "wxapp"))) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
            assertThatThrownBy(
                            () -> client.parse(LocalDate.parse("2026-10-06"), "TRADE", hash, data))
                    .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        }
    }
}
