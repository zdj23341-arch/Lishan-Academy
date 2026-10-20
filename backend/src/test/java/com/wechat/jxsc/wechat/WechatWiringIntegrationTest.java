package com.wechat.jxsc.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechat.jxsc.distribution.TransferProvider;
import com.wechat.jxsc.payment.PaymentProvider;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"jxsc.providers.payment=REAL", "jxsc.providers.transfer=REAL"})
class WechatWiringIntegrationTest extends MySqlIntegrationTest {
    @Autowired PaymentProvider payment;
    @Autowired TransferProvider transfer;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    @Test
    void realModeSelectsActualChannelEvenBeforeDeploymentCredentialsArrive() {
        assertThat(payment.channel()).isEqualTo("WECHAT_PAY");
        assertThat(transfer.channel()).isEqualTo("WECHAT_TRANSFER");
        assertThat(transfer.ready()).isFalse();
    }

    @Test
    void statementSourcesAndIssuesExistWithoutCreatingASecondFinancialLedger() {
        assertThat(
                        db.queryForList(
                                "select table_name from information_schema.tables where"
                                        + " table_schema=database()",
                                String.class))
                .contains("channel_statement", "reconciliation_issue", "wechat_evidence");
    }
}
