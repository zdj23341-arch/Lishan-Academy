package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.auth.wechat.*;
import com.wechat.jxsc.distribution.*;
import com.wechat.jxsc.payment.*;
import com.wechat.jxsc.payment.wechat.*;
import com.wechat.jxsc.wechat.WechatTestServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Import(WechatPersistenceIntegrationTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
@TestPropertySource(
        properties = {
            "jxsc.providers.payment=REAL",
            "jxsc.providers.transfer=REAL",
            "jxsc.wechat.app-id=wxapp",
            "jxsc.wechat.merchant-id=1900001",
            "jxsc.wechat.pay.book-jsapi-approved=true",
            "jxsc.wechat.pay.notify-base-url=https://merchant.example",
            "jxsc.wechat.transfer.enabled=true",
            "jxsc.wechat.transfer.scene-id=APPROVED_SCENE",
            "jxsc.wechat.transfer.scene-reports=[{\"info_type\":\"岗位类型\",\"info_content\":\"佣金报酬\"}]",
            "jxsc.wechat.transfer.approval-reference=test-only",
            "jxsc.wechat.transfer.remark=佣金报酬"
        })
class WechatPersistenceIntegrationTest extends CommerceTestSupport {
    static final WechatTestServer SERVER = new WechatTestServer();

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock fixtureClock() {
            return new FixtureClock();
        }

        @Bean
        @Primary
        WechatHttp fixtureTransport(Clock clock) {
            return SERVER.transport(clock, "PUB1");
        }
    }

    static final class FixtureClock extends Clock {
        private volatile Instant now =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        public Instant instant() {
            return now;
        }

        void set(Instant value) {
            now = value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void cryptoConfiguration(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("jxsc.wechat.pay.encryption-key-id", () -> "PUB1");
    }

    @Autowired WechatLoginService login;
    @Autowired PaymentTransactions payments;
    @Autowired PaymentWorkflow paymentWorkflow;
    @Autowired SessionService sessions;
    @Autowired WechatStatementReconciliation reconciliation;
    @Autowired WechatStatements statements;
    @Autowired WithdrawalWorkflow transfers;
    @Autowired TransferProvider transferProvider;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @Autowired Clock clock;
    @Autowired WithdrawalTransactions withdrawalTransactions;
    @Autowired WechatStatementWorker statementWorker;
    @Autowired WechatSettings wechatSettings;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @BeforeEach
    void setup() {
        ((FixtureClock) clock).set(Instant.now());
        SERVER.requests.clear();
        SERVER.failure = null;
        SERVER.responder = r -> SERVER.jsonResponse(200, Map.of("prepay_id", "private-prepay"));
    }

    @AfterEach
    void server() {
        environment.getPropertySources().remove("wechat-review-override");
        assertThat(SERVER.failure).isNull();
    }

    void configure(String key, String value) {
        environment
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "wechat-review-override", Map.of("jxsc.wechat." + key, value)));
    }

    record TransferFixture(long id, String did, String token, String bill) {}

    TransferFixture withdrawal(String name) throws Exception {
        String admin = admin("SUPER_ADMIN");
        var owner = customer();
        String token = owner.accessToken();
        String did =
                call(
                                "POST",
                                "/admin/distributors",
                                admin,
                                Map.of("userId", owner.user().id(), "reason", "Review fixture"),
                                200)
                        .path("id")
                        .asText();
        call(
                "POST",
                "/distribution/receipt-profile",
                token,
                name == null ? Map.of() : Map.of("realName", name),
                200);
        jdbc.update("update commission_account set available_fen=1000 where distributor_id=?", did);
        long id =
                write("/distribution/withdrawals", token, Map.of("amountFen", 100))
                        .path("id")
                        .asLong();
        return new TransferFixture(
                id,
                did,
                token,
                jdbc.queryForObject(
                        "select out_bill_no from withdrawal_request where id=?", String.class, id));
    }

    void waitResponse(TransferFixture f) {
        SERVER.responder =
                r ->
                        SERVER.jsonResponse(
                                200,
                                Map.of(
                                        "out_bill_no",
                                        f.bill(),
                                        "transfer_bill_no",
                                        "WX" + f.bill(),
                                        "mch_id",
                                        "1900001",
                                        "appid",
                                        "wxapp",
                                        "transfer_amount",
                                        100,
                                        "state",
                                        "WAIT_USER_CONFIRM",
                                        "package_info",
                                        "review-private-package"));
    }

    @Test
    void identicalAuthenticatedQueriesKeepActionablePackageAndDoNotRepeatAccounting()
            throws Exception {
        var f = withdrawal(null);
        waitResponse(f);
        for (int i = 0; i < 3; i++) {
            jdbc.update("update withdrawal_request set next_query_at=null where id=?", f.id());
            assertThatThrownBy(() -> transfers.run(f.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            var view = call("GET", "/distribution/withdrawals/" + f.id(), f.token(), null, 200);
            assertThat(view.path("status").asText()).isEqualTo("USER_ACTION_REQUIRED");
            assertThat(view.path("confirmation").path("packageInfo").asText())
                    .isEqualTo("review-private-package");
        }
        assertThat(SERVER.requests.stream().filter(r -> r.method().equals("POST")).count())
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " withdrawal_id=?",
                                Integer.class,
                                f.id()))
                .isEqualTo(1);
    }

    @Test
    void oldUnsentRequestCanDispatchAndItsQueryWindowStartsAtDispatch() throws Exception {
        var f = withdrawal(null);
        waitResponse(f);
        jdbc.update(
                "update withdrawal_request set created_at=? where id=?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(60))),
                f.id());
        assertThatThrownBy(() -> transfers.run(f.id()))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        assertThat(SERVER.requests).hasSize(1);
        assertThat(SERVER.requests.getFirst().method()).isEqualTo("POST");
        jdbc.update("update withdrawal_request set next_query_at=null where id=?", f.id());
        assertThatThrownBy(() -> transfers.run(f.id()))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        assertThat(SERVER.requests).hasSize(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='TRANSFER_QUERY_RETENTION_LIMIT'",
                                Integer.class,
                                f.bill()))
                .isZero();
    }

    @Test
    void retentionAlertsAt29DaysAndStopsQueriesAt30DaysFromDispatch() throws Exception {
        var f = withdrawal(null);
        waitResponse(f);
        assertThatThrownBy(() -> transfers.run(f.id()))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        for (long elapsed :
                new long[] {29 * 86400L - 1, 29 * 86400L, 30 * 86400L - 1, 30 * 86400L}) {
            jdbc.update(
                    "update withdrawal_request set dispatch_started_at=?,next_query_at=null where"
                            + " id=?",
                    java.sql.Timestamp.from(clock.instant().minusSeconds(elapsed)),
                    f.id());
            withdrawalTransactions.alertIfOverdue(f.id());
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from reconciliation_issue where business_no=?"
                                            + " and issue_code='TRANSFER_QUERY_RETENTION_LIMIT'",
                                    Integer.class,
                                    f.bill()))
                    .isEqualTo(elapsed < 29 * 86400L ? 0 : 1);
            int before = SERVER.requests.size();
            assertThatThrownBy(() -> transfers.run(f.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            assertThat(SERVER.requests.size()).isEqualTo(before + (elapsed < 30 * 86400L ? 1 : 0));
        }
    }

    @Test
    void invalidCreatePrerequisitesLeaveOriginalIntentUnsentAndRecoverAfterCorrection()
            throws Exception {
        for (var config :
                List.of(
                        Map.entry("transfer.scene-reports", "[]"),
                        Map.entry("transfer.scene-reports", "malformed"),
                        Map.entry("pay.notify-base-url", "http://invalid"),
                        Map.entry("pay.encryption-key-id", "UNKNOWN"),
                        Map.entry("pay.encryption-key-id", ""))) {
            var f = withdrawal("张三");
            waitResponse(f);
            configure(config.getKey(), config.getValue());
            assertThatThrownBy(() -> transfers.run(f.id())).isInstanceOf(RuntimeException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "select dispatch_started_at is null from withdrawal_request"
                                            + " where id=?",
                                    Boolean.class,
                                    f.id()))
                    .isTrue();
            assertThat(SERVER.requests).isEmpty();
            environment.getPropertySources().remove("wechat-review-override");
            assertThatThrownBy(() -> transfers.run(f.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            assertThat(SERVER.requests.getFirst().body().path("out_bill_no").asText())
                    .isEqualTo(f.bill());
            SERVER.requests.clear();
        }
    }

    @Test
    void readinessRejectsMalformedCreateCapability() {
        for (var config :
                List.of(
                        Map.entry("transfer.scene-reports", "[]"),
                        Map.entry("transfer.scene-reports", "malformed"),
                        Map.entry("pay.notify-base-url", "http://invalid"),
                        Map.entry("pay.encryption-key-id", "UNKNOWN"))) {
            configure(config.getKey(), config.getValue());
            assertThat(transferProvider.ready()).as(config.getKey()).isFalse();
        }
    }

    @Test
    void dispatchedQueriesRecoverWhenCreateConfigurationIsInvalidButNewSendsStayBlocked()
            throws Exception {
        for (var config :
                List.of(
                        Map.entry("transfer.scene-reports", "malformed"),
                        Map.entry("pay.notify-base-url", "http://invalid"),
                        Map.entry("pay.encryption-key-id", "RETIRED"))) {
            var sent = withdrawal(null);
            waitResponse(sent);
            assertThatThrownBy(() -> transfers.run(sent.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            var unsent = withdrawal(null);
            configure(config.getKey(), config.getValue());
            assertThat(transferProvider.ready()).isFalse();
            write(
                    "POST",
                    "/distribution/withdrawals",
                    unsent.token(),
                    Map.of("amountFen", 100),
                    UUID.randomUUID().toString(),
                    503);
            int before = SERVER.requests.size();
            assertThatThrownBy(() -> transfers.run(unsent.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            assertThat(SERVER.requests).hasSize(before);
            assertThat(
                            jdbc.queryForObject(
                                    "select dispatch_started_at is null from withdrawal_request"
                                            + " where id=?",
                                    Boolean.class,
                                    unsent.id()))
                    .isTrue();
            jdbc.update("update withdrawal_request set next_query_at=null where id=?", sent.id());
            assertThatThrownBy(() -> transfers.run(sent.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            assertThat(SERVER.requests).hasSize(before + 1);
            assertThat(SERVER.requests.getLast().method()).isEqualTo("GET");
            assertThat(SERVER.requests.getLast().path()).endsWith("/out-bill-no/" + sent.bill());
            var detail =
                    call("GET", "/distribution/withdrawals/" + sent.id(), sent.token(), null, 200);
            assertThat(detail.path("status").asText()).isEqualTo("USER_ACTION_REQUIRED");
            assertThat(detail.path("confirmation").path("packageInfo").asText())
                    .isEqualTo("review-private-package");
            environment.getPropertySources().remove("wechat-review-override");
            waitResponse(unsent);
            assertThatThrownBy(() -> transfers.run(unsent.id()))
                    .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
            assertThat(SERVER.requests.getLast().method()).isEqualTo("POST");
            assertThat(SERVER.requests.getLast().body().path("out_bill_no").asText())
                    .isEqualTo(unsent.bill());
            SERVER.requests.clear();
        }
    }

    Map<String, Object> transferResource(TransferFixture fixture, String state) {
        var intent = withdrawalTransactions.channelIntent(fixture.id());
        return new HashMap<>(
                Map.of(
                        "out_bill_no",
                        fixture.bill(),
                        "transfer_bill_no",
                        "WX" + fixture.bill(),
                        "mch_id",
                        "1900001",
                        "appid",
                        "wxapp",
                        "transfer_amount",
                        100,
                        "openid",
                        intent.recipient(),
                        "state",
                        state));
    }

    @Test
    void authenticatedCallbacksStillValidateIdentityAndSettleWithRetiredCreateEncryptionKey()
            throws Exception {
        var f = withdrawal(null);
        waitResponse(f);
        assertThatThrownBy(() -> transfers.run(f.id()))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        configure("pay.encryption-key-id", "RETIRED");
        var resource = transferResource(f, "SUCCESS");
        resource.put("openid", "wrong-recipient");
        callback(
                "transfers",
                SERVER.callback(
                        "wrong-" + UUID.randomUUID(), "MCHTRANSFER.BILL.FINISHED", resource),
                502);
        assertThat(
                        jdbc.queryForObject(
                                "select paid_out_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                f.did()))
                .isZero();
        byte[] valid =
                SERVER.callback(
                        "valid-" + UUID.randomUUID(),
                        "MCHTRANSFER.BILL.FINISHED",
                        transferResource(f, "SUCCESS"));
        callback("transfers", valid, 204);
        callback("transfers", valid, 204);
        assertThat(
                        jdbc.queryForObject(
                                "select paid_out_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                f.did()))
                .isEqualTo(100);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " business_key=?",
                                Integer.class,
                                "withdraw-settle:" + f.id()))
                .isEqualTo(1);
    }

    @Test
    void cancelAndConflictingTerminalReconciliationRecoverWithInvalidCreateCallbackUrl()
            throws Exception {
        var f = withdrawal(null);
        waitResponse(f);
        assertThatThrownBy(() -> transfers.run(f.id()))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        configure("pay.notify-base-url", "http://invalid");
        write("/distribution/withdrawals/" + f.id() + "/cancel", f.token(), Map.of());
        var cancellingResource = transferResource(f, "CANCELING");
        SERVER.responder = r -> SERVER.jsonResponse(200, cancellingResource);
        assertThatThrownBy(() -> transfers.cancel(f.id()))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        assertThat(SERVER.requests.stream().filter(r -> r.path().endsWith("/cancel")).count())
                .isEqualTo(1);
        byte[] cancelled =
                SERVER.callback(
                        "cancelled-" + UUID.randomUUID(),
                        "MCHTRANSFER.BILL.FINISHED",
                        transferResource(f, "CANCELLED"));
        callback("transfers", cancelled, 204);
        callback("transfers", cancelled, 204);
        byte[] late =
                SERVER.callback(
                        "late-" + UUID.randomUUID(),
                        "MCHTRANSFER.BILL.FINISHED",
                        transferResource(f, "SUCCESS"));
        callback("transfers", late, 204);
        callback("transfers", late, 204);
        var successResource = transferResource(f, "SUCCESS");
        SERVER.responder = r -> SERVER.jsonResponse(200, successResource);
        transfers.reconcile(f.id());
        assertThat(SERVER.requests.getLast().path()).endsWith("/out-bill-no/" + f.bill());
        assertThat(jdbc.queryForMap("select status from withdrawal_request where id=?", f.id()))
                .containsEntry("status", "CLOSED");
        assertThat(
                        jdbc.queryForMap(
                                "select status,observed_paid_out_fen from withdrawal_reconciliation"
                                        + " where withdrawal_id=?",
                                f.id()))
                .containsEntry("status", "NEEDS_REVIEW")
                .containsEntry("observed_paid_out_fen", 100L);
        assertThat(
                        jdbc.queryForObject(
                                "select paid_out_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                f.did()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " business_key=?",
                                Integer.class,
                                "withdraw-release:" + f.id()))
                .isEqualTo(1);
    }

    @Test
    void transferFailureViewsExposeOnlyAllowlistedSafeReason() throws Exception {
        for (String reason :
                List.of("PAYEE_ACCOUNT_ABNORMAL", "private-openid 张三 arbitrary reason")) {
            var f = withdrawal(null);
            SERVER.responder =
                    r ->
                            SERVER.jsonResponse(
                                    200,
                                    Map.of(
                                            "out_bill_no",
                                            f.bill(),
                                            "transfer_bill_no",
                                            "WX" + f.bill(),
                                            "state",
                                            "FAIL",
                                            "fail_reason",
                                            reason));
            transfers.run(f.id());
            for (var view :
                    List.of(
                            call(
                                    "GET",
                                    "/distribution/withdrawals/" + f.id(),
                                    f.token(),
                                    null,
                                    200),
                            call(
                                    "GET",
                                    "/admin/withdrawals/" + f.id(),
                                    admin("SUPER_ADMIN"),
                                    null,
                                    200))) {
                assertThat(view.path("errorCode").asText())
                        .isEqualTo(
                                reason.equals("PAYEE_ACCOUNT_ABNORMAL")
                                        ? reason
                                        : "WECHAT_TRANSFER_FAILED");
                assertThat(view.toString()).doesNotContain("private-openid", "张三");
            }
        }
    }

    @Test
    void paymentSuccessCallbackRequiresReturnedCurrencyAndPayer() throws Exception {
        String admin = admin("SUPER_ADMIN");
        var buyer = customer();
        String token = buyer.accessToken();
        var address = address(token);
        var order =
                order(token, quote(token, book(admin, 5, 2000), address.path("id").asText(), 1));
        write("/payments/wechat/prepay", token, Map.of("orderId", order.path("id").asText()));
        long id =
                jdbc.queryForObject(
                        "select id from payment_transaction where order_id=?",
                        Long.class,
                        order.path("id").asText());
        var intent = payments.payment(id);
        for (String missing :
                List.of("currency", "payer", "openid", "wrong-currency", "wrong-payer")) {
            var resource = new HashMap<String, Object>(payResource(intent, "SUCCESS", 2600));
            if (missing.equals("currency")) resource.put("amount", Map.of("total", 2600));
            if (missing.equals("payer")) resource.remove("payer");
            if (missing.equals("openid")) resource.put("payer", Map.of());
            if (missing.equals("wrong-currency"))
                resource.put("amount", Map.of("total", 2600, "currency", "USD"));
            if (missing.equals("wrong-payer"))
                resource.put("payer", Map.of("openid", "other-recipient"));
            callback(
                    "payments",
                    SERVER.callback(
                            "review-pay-" + UUID.randomUUID(), "TRANSACTION.SUCCESS", resource),
                    502);
            assertThat(
                            jdbc.queryForObject(
                                    "select state from payment_transaction where id=?",
                                    String.class,
                                    id))
                    .isEqualTo("PROCESSING");
        }
        byte[] valid =
                SERVER.callback(
                        "review-pay-" + UUID.randomUUID(),
                        "TRANSACTION.SUCCESS",
                        payResource(intent, "SUCCESS", 2600));
        callback("payments", valid, 204);
        callback("payments", valid, 204);
        assertThat(
                        jdbc.queryForObject(
                                "select state from payment_transaction where id=?",
                                String.class,
                                id))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void signedAbsentFundAccountCompletesCoverageAndLaterAvailabilityKeepsHistory()
            throws Exception {
        ((FixtureClock) clock).set(clock.instant().minus(Duration.ofDays(1)));
        var f = withdrawal(null);
        SERVER.responder =
                r ->
                        SERVER.jsonResponse(
                                200,
                                Map.of(
                                        "out_bill_no",
                                        f.bill(),
                                        "transfer_bill_no",
                                        "WX" + f.bill(),
                                        "state",
                                        "SUCCESS"));
        transfers.run(f.id());
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai"));
        String header = "记账时间,微信支付业务单号,资金流水单号,业务名称,业务类型,收支类型,收支金额(元),账户结余(元),资金变更提交申请人,备注,业务凭证号\n";
        String source =
                header
                        + "`"
                        + day
                        + " 12:00:00,`OTHER,`FLOW,`充值/提现,`充值,`收入,`1.00,`1.00,`system,`,\n"
                        + "资金流水总笔数\n"
                        + "`1\n";
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash =
                HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
        SERVER.responder =
                r ->
                        r.path().contains("OPERATION")
                                ? SERVER.jsonResponse(
                                        400,
                                        Map.of(
                                                "code",
                                                "NO_STATEMENT_EXIST",
                                                "message",
                                                "private detail"))
                                : r.path().startsWith("/v3/billdownload/")
                                        ? new WechatTestServer.Response(200, bytes, false)
                                        : SERVER.jsonResponse(
                                                200,
                                                Map.of(
                                                        "hash_type",
                                                        "SHA1",
                                                        "hash_value",
                                                        hash,
                                                        "download_url",
                                                        SERVER.origin() + "/v3/billdownload/file"));
        var client =
                new WechatStatements(
                        SERVER.transport(clock),
                        wechatSettings,
                        Clock.offset(clock, Duration.ofDays(1)));
        var basic = client.download(day, "FUND_BASIC");
        java.util.concurrent.atomic.AtomicReference<WechatStatements.Statement> absent =
                new java.util.concurrent.atomic.AtomicReference<>();
        assertThatCode(() -> absent.set(client.download(day, "FUND_OPERATION")))
                .doesNotThrowAnyException();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> reconciliation.reconcile(basic));
            var b = pool.submit(() -> reconciliation.reconcile(absent.get()));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        reconciliation.reconcile(absent.get());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from channel_statement where bill_date=? and"
                                        + " statement_type='FUND_OPERATION'",
                                Integer.class,
                                day))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='TRANSFER_FUND_ROW_MISSING_REQUIRES_QUERY'",
                                Integer.class,
                                f.bill()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from wechat_statement_availability where"
                                        + " bill_date=? and statement_type='FUND_OPERATION' and"
                                        + " availability='ABSENT'",
                                Integer.class,
                                day))
                .isEqualTo(1);
        String later =
                header
                        + "`"
                        + day
                        + " 12:00:00,`WX"
                        + f.bill()
                        + ",`FLOW2,`企业付款,`转账,`支出,`1.00,`0.00,`system,`,`"
                        + f.bill()
                        + "\n资金流水总笔数\n`1\n";
        reconciliation.reconcile(parsedFund(day, "FUND_OPERATION", later));
        reconciliation.reconcile(absent.get());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from wechat_statement_availability where"
                                        + " bill_date=? and statement_type='FUND_OPERATION'",
                                Integer.class,
                                day))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select paid_out_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                f.did()))
                .isEqualTo(100);
        assertThat(
                        jdbc.queryForObject(
                                "select status from withdrawal_request where id=?",
                                String.class,
                                f.id()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void creatingAndUnverifiedStatementResponsesKeepCoverageUnresolved() {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(3).toString();
        SERVER.responder = r -> SERVER.jsonResponse(400, Map.of("code", "STATEMENT_CREATING"));
        assertThatThrownBy(
                        () -> statementWorker.handle("WECHAT_STATEMENT", day + ":FUND_BASIC", "{}"))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from wechat_statement_availability where"
                                        + " bill_date=? and availability='CREATING'",
                                Integer.class,
                                day))
                .isEqualTo(1);
        SERVER.responder =
                r ->
                        new WechatTestServer.Response(
                                400,
                                "{\"code\":\"NO_STATEMENT_EXIST\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                false);
        assertThatThrownBy(
                        () ->
                                statementWorker.handle(
                                        "WECHAT_STATEMENT", day + ":FUND_OPERATION", "{}"))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from wechat_statement_availability where"
                                        + " bill_date=? and availability='ABSENT'",
                                Integer.class,
                                day))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where bill_date=? and"
                                        + " issue_code='STATEMENT_COVERAGE_UNRESOLVED'",
                                Integer.class,
                                day))
                .isEqualTo(2);
    }

    AuthDtos.CustomerLogin customer() {
        return login.provision(
                new WechatLoginClient.Identity("wxapp", "open-" + UUID.randomUUID(), "same-union"),
                "contract-test");
    }

    @Test
    void concurrentLoginNamespacesIdentityAndProvisionsImmutableAnchors(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String open = "race-" + UUID.randomUUID();
        var identity = new WechatLoginClient.Identity("wxapp", open, "same-union");
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<AuthDtos.CustomerLogin>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                results.add(pool.submit(() -> login.provision(identity, "test")));
            Set<String> ids = new HashSet<>();
            for (var future : results) ids.add(future.get(15, TimeUnit.SECONDS).user().id());
            assertThat(ids).hasSize(1);
            String id = ids.iterator().next();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from entitlement_subject where user_id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from distribution_identity where user_id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
            assertThat(
                            login.provision(
                                            new WechatLoginClient.Identity(
                                                    "other-app", open, "same-union"),
                                            "test")
                                    .user()
                                    .id())
                    .isNotEqualTo(id);
            assertThat(output.getAll()).doesNotContain(open);
        }
    }

    @Test
    void currentTransferReceiptCancellationAndLateSuccessKeepAccountingSafe() throws Exception {
        String admin = admin("SUPER_ADMIN");
        var owner = customer();
        String token = owner.accessToken();
        var distributor =
                call(
                        "POST",
                        "/admin/distributors",
                        admin,
                        Map.of("userId", owner.user().id(), "reason", "Contract fixture"),
                        200);
        String did = distributor.path("id").asText();
        call("POST", "/distribution/receipt-profile", token, Map.of("realName", "张三"), 200);
        jdbc.update("update commission_account set available_fen=1000 where distributor_id=?", did);
        SERVER.responder =
                r ->
                        SERVER.jsonResponse(
                                200,
                                Map.of(
                                        "out_bill_no",
                                        r.body().path("out_bill_no").asText(),
                                        "transfer_bill_no",
                                        "WXTRANS1",
                                        "state",
                                        "WAIT_USER_CONFIRM",
                                        "package_info",
                                        "private-receipt"));
        var w = write("/distribution/withdrawals", token, Map.of("amountFen", 100));
        String id = w.path("id").asText();
        assertThatThrownBy(() -> transfers.run(Long.parseLong(id)))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        var detail = call("GET", "/distribution/withdrawals/" + id, token, null, 200);
        assertThat(detail.path("confirmation").path("packageInfo").asText())
                .isEqualTo("private-receipt");
        assertThat(call("GET", "/distribution/withdrawals", token, null, 200).toString())
                .doesNotContain("private-receipt");
        write("/distribution/withdrawals/" + id + "/cancel", token, Map.of());
        assertThat(
                        jdbc.queryForObject(
                                "select withdrawing_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                did))
                .isEqualTo(100);
        String bill = w.path("outBillNo").asText();
        if (bill.isBlank())
            bill =
                    jdbc.queryForObject(
                            "select out_bill_no from withdrawal_request where id=?",
                            String.class,
                            id);
        Map<String, Object> canceling =
                Map.of(
                        "mch_id",
                        "1900001",
                        "appid",
                        "wxapp",
                        "out_bill_no",
                        bill,
                        "transfer_bill_no",
                        "WXTRANS1",
                        "transfer_amount",
                        100,
                        "state",
                        "CANCELING");
        SERVER.responder = r -> SERVER.jsonResponse(200, canceling);
        assertThatThrownBy(() -> transfers.cancel(Long.parseLong(id)))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        assertThat(
                        jdbc.queryForObject(
                                "select withdrawing_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                did))
                .isEqualTo(100);
        var cancelled = new HashMap<String, Object>(canceling);
        cancelled.put("state", "CANCELLED");
        cancelled.remove("appid");
        cancelled.put(
                "openid",
                jdbc.queryForObject(
                        "select openid from user_wechat_identity where user_id=? and"
                                + " app_id='wxapp'",
                        String.class,
                        owner.user().id()));
        byte[] closed =
                SERVER.callback(
                        "closed-" + UUID.randomUUID(), "MCHTRANSFER.BILL.FINISHED", cancelled);
        callback("transfers", closed, 204);
        callback("transfers", closed, 204);
        assertThat(
                        jdbc.queryForObject(
                                "select available_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                did))
                .isEqualTo(1000);
        cancelled.put("state", "SUCCESS");
        byte[] late =
                SERVER.callback(
                        "late-" + UUID.randomUUID(), "MCHTRANSFER.BILL.FINISHED", cancelled);
        callback("transfers", late, 204);
        callback("transfers", late, 204);
        assertThat(
                        jdbc.queryForObject(
                                "select status from withdrawal_request where id=?",
                                String.class,
                                id))
                .isEqualTo("CLOSED");
        assertThat(
                        jdbc.queryForObject(
                                "select paid_out_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                did))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select observed_paid_out_fen from withdrawal_reconciliation where"
                                        + " withdrawal_id=?",
                                Long.class,
                                id))
                .isEqualTo(100);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " business_key=?",
                                Integer.class,
                                "withdraw-release:" + id))
                .isEqualTo(1);
    }

    @Test
    void realPrepayFreezesServerContextDuplicateQueriesAndSignedCallbacksSettleOnce()
            throws Exception {
        String admin = admin("SUPER_ADMIN");
        var buyer = customer();
        String token = buyer.accessToken(), product = book(admin, 10, 2000);
        var address = address(token);
        var order = order(token, quote(token, product, address.path("id").asText(), 1));
        String orderId = order.path("id").asText();
        var prepay = write("/payments/wechat/prepay", token, Map.of("orderId", orderId));
        long pid = prepay.path("paymentId").asLong();
        if (pid == 0)
            pid =
                    jdbc.queryForObject(
                            "select id from payment_transaction where order_id=?",
                            Long.class,
                            orderId);
        var intent = payments.payment(pid);
        assertThat(intent.businessNo()).matches("[A-Za-z0-9]{32}");
        assertThat(intent.payer()).startsWith("open-");
        assertThat(intent.amountFen()).isEqualTo(2600);
        assertThat(
                        jdbc.queryForObject(
                                "select dispatch_started_at is not null from payment_transaction"
                                        + " where id=?",
                                Boolean.class,
                                pid))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select params_json from payment_transaction where id=?",
                                String.class,
                                pid))
                .isNull();
        Map<String, Object> resource = payResource(intent, "NOTPAY", 2600);
        SERVER.responder = r -> SERVER.jsonResponse(200, resource);
        write("/payments/wechat/prepay", token, Map.of("orderId", orderId));
        assertThat(
                        SERVER.requests.stream()
                                .filter(r -> r.path().equals("/v3/pay/transactions/jsapi"))
                                .count())
                .isEqualTo(1);
        byte[] wrong =
                SERVER.callback(
                        "wrong-" + UUID.randomUUID(),
                        "TRANSACTION.SUCCESS",
                        payResource(intent, "SUCCESS", 2601));
        callback("payments", wrong, 502);
        assertThat(
                        jdbc.queryForObject(
                                "select state from payment_transaction where id=?",
                                String.class,
                                pid))
                .isEqualTo("PROCESSING");
        byte[] success =
                SERVER.callback(
                        "success-" + UUID.randomUUID(),
                        "TRANSACTION.SUCCESS",
                        payResource(intent, "SUCCESS", 2600));
        callback("payments", success, 204);
        callback("payments", success, 204);
        assertThat(
                        jdbc.queryForObject(
                                "select state from payment_transaction where id=?",
                                String.class,
                                pid))
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_task where task_type='ORDER_PAID' and"
                                        + " business_key=?",
                                Integer.class,
                                orderId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from callback_inbox where provider='WECHAT_PAY'"
                                        + " and status='PROCESSED' and payload_encrypted like"
                                        + " '%open-%'",
                                Integer.class))
                .isZero();
        String refund = "R" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "insert into"
                    + " refund_transaction(order_id,payment_id,out_refund_no,channel,goods_fen,freight_fen,state,reason,exceptional,restock_quantity,created_at,updated_at)"
                    + " values(?,?,?,'WECHAT_PAY',40,0,'CREATED','Contract"
                    + " fixture',true,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                orderId,
                pid,
                refund);
        long rid =
                jdbc.queryForObject(
                        "select id from refund_transaction where out_refund_no=?",
                        Long.class,
                        refund);
        Map<String, Object> rr =
                new HashMap<>(
                        Map.of(
                                "out_refund_no",
                                refund,
                                "out_trade_no",
                                intent.businessNo(),
                                "transaction_id",
                                "WX" + intent.businessNo(),
                                "refund_id",
                                "WX" + refund,
                                "status",
                                "ABNORMAL",
                                "amount",
                                Map.of("refund", 40, "total", 2600, "currency", "CNY")));
        SERVER.responder = r -> SERVER.jsonResponse(200, rr);
        paymentWorkflow.syncRefund(rid);
        assertThat(
                        jdbc.queryForObject(
                                "select state from refund_transaction where id=?",
                                String.class,
                                rid))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='REFUND_ABNORMAL'",
                                Integer.class,
                                refund))
                .isEqualTo(1);
        rr.remove("status");
        rr.put("refund_status", "SUCCESS");
        rr.put("mchid", "1900001");
        rr.put("amount", Map.of("refund", 40, "total", 2600));
        byte[] refunded = SERVER.callback("refund-" + UUID.randomUUID(), "REFUND.SUCCESS", rr);
        callback("refunds", refunded, 204);
        callback("refunds", refunded, 204);
        assertThat(
                        jdbc.queryForObject(
                                "select state from refund_transaction where id=?",
                                String.class,
                                rid))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void workerRecoversCommittedPrepayIntentBeforeAnyNetworkDispatch() throws Exception {
        String admin = admin("SUPER_ADMIN");
        var buyer = customer();
        String token = buyer.accessToken();
        var address = address(token);
        String product = book(admin, 5, 2000);
        var order = order(token, quote(token, product, address.path("id").asText(), 1));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                sessions.authenticate(token), null, List.of()));
        PaymentProvider.Intent intent;
        try {
            intent =
                    payments.prepare(
                            Long.parseLong(order.path("id").asText()),
                            UUID.randomUUID().toString(),
                            false);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        try {
            paymentWorkflow.syncPayment(intent.id());
        } catch (com.wechat.jxsc.common.ApiException ignored) {
        }
        assertThat(SERVER.requests.getFirst().method()).isEqualTo("POST");
        assertThat(SERVER.requests.getFirst().path()).isEqualTo("/v3/pay/transactions/jsapi");
    }

    @Test
    void lostTransferCreateQueriesOriginalAndNeverInventsReceiptOrResends() throws Exception {
        String admin = admin("SUPER_ADMIN");
        var owner = customer();
        String token = owner.accessToken();
        var d =
                call(
                        "POST",
                        "/admin/distributors",
                        admin,
                        Map.of("userId", owner.user().id(), "reason", "Recovery fixture"),
                        200);
        String did = d.path("id").asText();
        call("POST", "/distribution/receipt-profile", token, Map.of(), 200);
        jdbc.update("update commission_account set available_fen=1000 where distributor_id=?", did);
        SERVER.responder = r -> SERVER.jsonResponse(500, Map.of("code", "SYSTEM_ERROR"));
        var withdrawal = write("/distribution/withdrawals", token, Map.of("amountFen", 100));
        long id = withdrawal.path("id").asLong();
        assertThatThrownBy(() -> transfers.run(id))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        String bill =
                jdbc.queryForObject(
                        "select out_bill_no from withdrawal_request where id=?", String.class, id);
        SERVER.responder =
                r ->
                        SERVER.jsonResponse(
                                200,
                                Map.of(
                                        "out_bill_no",
                                        bill,
                                        "transfer_bill_no",
                                        "WX" + bill,
                                        "mch_id",
                                        "1900001",
                                        "appid",
                                        "wxapp",
                                        "transfer_amount",
                                        100,
                                        "state",
                                        "WAIT_USER_CONFIRM"));
        jdbc.update("update withdrawal_request set next_query_at=null where id=?", id);
        assertThatThrownBy(() -> transfers.run(id))
                .isInstanceOf(com.wechat.jxsc.common.tasks.TaskHandler.RetryLater.class);
        var view = call("GET", "/distribution/withdrawals/" + id, token, null, 200);
        assertThat(view.path("confirmation").isNull()).isTrue();
        assertThat(view.path("errorCode").asText()).isEqualTo("CONFIRMATION_PACKAGE_UNAVAILABLE");
        assertThat(SERVER.requests.stream().filter(r -> r.method().equals("POST")).count())
                .isEqualTo(1);
        assertThat(SERVER.requests.getLast().path()).endsWith("/out-bill-no/" + bill);
        assertThat(
                        jdbc.queryForObject(
                                "select withdrawing_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                did))
                .isEqualTo(100);
    }

    Map<String, Object> payResource(PaymentProvider.Intent i, String state, long amount) {
        return Map.of(
                "appid",
                i.appId(),
                "mchid",
                i.merchantId(),
                "out_trade_no",
                i.businessNo(),
                "transaction_id",
                "WX" + i.businessNo(),
                "trade_state",
                state,
                "amount",
                Map.of("total", amount, "currency", "CNY"),
                "payer",
                Map.of("openid", i.payer()));
    }

    void callback(String kind, byte[] body, int expected) throws Exception {
        var request =
                MockMvcRequestBuilders.post("/api/v1/webhooks/wechat/" + kind)
                        .contentType("application/json")
                        .content(body);
        SERVER.headers(body).forEach(request::header);
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
    }

    @Test
    void statementHashAndDuplicateIssuesAreReadOnlyAgainstBalances() throws Exception {
        long before =
                jdbc.queryForObject(
                        "select coalesce(sum(available_fen+withdrawing_fen+paid_out_fen),0) from"
                                + " commission_account",
                        Long.class);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        String source =
                "交易时间,公众账号ID,商户号,货币种类,商户订单号,微信订单号,交易状态,订单金额,商户退款单号,微信退款单号,申请退款金额,退款状态\n`"
                        + date
                        + " 12:00:00,`wxapp,`1900001,`CNY,`MISSING123,`WX123,`SUCCESS,`1.23,`0,`0,`0.00,`\n"
                        + "总交易单数\n"
                        + "`1\n";
        byte[] data = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash =
                HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
        assertThatThrownBy(() -> statements.parse(date, "TRADE", "0".repeat(40), data))
                .isInstanceOf(com.wechat.jxsc.common.ApiException.class);
        var statement = statements.parse(date, "TRADE", hash, data);
        reconciliation.reconcile(statement);
        reconciliation.reconcile(statement);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where"
                                        + " business_no='MISSING123' and"
                                        + " issue_code='CHANNEL_ROW_UNMATCHED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select coalesce(sum(available_fen+withdrawing_fen+paid_out_fen),0)"
                                        + " from commission_account",
                                Long.class))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select source_cipher from channel_statement where bill_date=? and"
                                        + " statement_type='TRADE'",
                                String.class,
                                date))
                .doesNotContain("MISSING123");
    }

    @Test
    void fundStatementsCompareBothAccountsAndFlagMissingTransfersWithoutSettlingDebits()
            throws Exception {
        String admin = admin("SUPER_ADMIN");
        var owner = customer();
        String token = owner.accessToken();
        String did =
                call(
                                "POST",
                                "/admin/distributors",
                                admin,
                                Map.of(
                                        "userId",
                                        owner.user().id(),
                                        "reason",
                                        "Fund statement fixture"),
                                200)
                        .path("id")
                        .asText();
        call("POST", "/distribution/receipt-profile", token, Map.of(), 200);
        jdbc.update("update commission_account set available_fen=1000 where distributor_id=?", did);
        List<String> bills = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String state = i == 2 ? "PROCESSING" : "SUCCESS";
            SERVER.responder =
                    r ->
                            SERVER.jsonResponse(
                                    200,
                                    Map.of(
                                            "out_bill_no",
                                            r.body().path("out_bill_no").asText(),
                                            "transfer_bill_no",
                                            "WX" + r.body().path("out_bill_no").asText(),
                                            "state",
                                            state));
            long id =
                    write("/distribution/withdrawals", token, Map.of("amountFen", 100))
                            .path("id")
                            .asLong();
            try {
                transfers.run(id);
            } catch (com.wechat.jxsc.common.tasks.TaskHandler.RetryLater expected) {
            }
            bills.add(
                    jdbc.queryForObject(
                            "select out_bill_no from withdrawal_request where id=?",
                            String.class,
                            id));
        }
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String header = "记账时间,微信支付业务单号,资金流水单号,业务名称,业务类型,收支类型,收支金额(元),账户结余(元),资金变更提交申请人,备注,业务凭证号\n";
        String source = header;
        for (int i : List.of(0, 2))
            source +=
                    "`"
                            + date
                            + " 12:00:00,`WX"
                            + bills.get(i)
                            + ",`FLOW"
                            + i
                            + ",`企业付款,`转账,`支出,`1.00,`0.00,`system,`,`"
                            + bills.get(i)
                            + "\n";
        source += "资金流水总笔数,收入笔数,收入金额,支出笔数,支出金额\n`2,`0,`0.00,`2,`2.00\n";
        var basic = parsedFund(date, "FUND_BASIC", source);
        reconciliation.reconcile(basic);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='TRANSFER_FUND_ROW_MISSING_REQUIRES_QUERY'",
                                Integer.class,
                                bills.get(1)))
                .isZero();
        var operation =
                parsedFund(
                        date,
                        "FUND_OPERATION",
                        header + "资金流水总笔数,收入笔数,收入金额,支出笔数,支出金额\n`0,`0,`0.00,`0,`0.00\n");
        reconciliation.reconcile(operation);
        reconciliation.reconcile(operation);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='TRANSFER_FUND_ROW_MISSING_REQUIRES_QUERY'",
                                Integer.class,
                                bills.get(1)))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='TRANSFER_FUND_ROW_MISSING_REQUIRES_QUERY'",
                                Integer.class,
                                bills.get(0)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from reconciliation_issue where business_no=? and"
                                        + " issue_code='FUND_FLOW_REQUIRES_QUERY'",
                                Integer.class,
                                bills.get(2)))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "select available_fen,withdrawing_fen,paid_out_fen from"
                                        + " commission_account where distributor_id=?",
                                did))
                .containsEntry("available_fen", 700L)
                .containsEntry("withdrawing_fen", 100L)
                .containsEntry("paid_out_fen", 200L);
    }

    private WechatStatements.Statement parsedFund(LocalDate date, String type, String source)
            throws Exception {
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return statements.parse(
                date,
                type,
                HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes)),
                bytes);
    }

    @Test
    void explicitStatementRefreshReschedulesCompletedImports() throws Exception {
        String admin = admin("SUPER_ADMIN");
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2);
        String path = "/admin/wechat/statements/" + date + "/refresh";
        call("POST", path, admin, Map.of(), 200);
        jdbc.update(
                "update outbox_task set status='DONE' where task_type='WECHAT_STATEMENT' and"
                        + " business_key like ?",
                date + ":%");
        call("POST", path, admin, Map.of(), 200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_task where"
                                    + " task_type='WECHAT_STATEMENT' and business_key like ? and"
                                    + " status='READY'",
                                Integer.class,
                                date + ":%"))
                .isEqualTo(3);
    }
}
