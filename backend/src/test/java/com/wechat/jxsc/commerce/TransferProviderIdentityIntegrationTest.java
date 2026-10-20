package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.commerce.SensitiveCipher;
import com.wechat.jxsc.common.tasks.TaskHandler;
import com.wechat.jxsc.distribution.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

class TransferProviderIdentityIntegrationTest extends DistributionTestSupport {
    @Autowired WithdrawalWorkflow workflow;
    @Autowired SensitiveCipher cipher;

    @ParameterizedTest
    @CsvSource({
        "SUCCESS,CHANNEL",
        "TIMEOUT,CHANNEL",
        "PROCESSING,CHANNEL",
        "USER_CONFIRM,CHANNEL",
        "FAILURE,CHANNEL",
        "CLOSED,CHANNEL",
        "SUCCESS_CALLBACK,CHANNEL",
        "FAILURE_CALLBACK,CHANNEL",
        "CONFIRM_RECEIPT,CHANNEL",
        "SUCCESS_QUERY,CHANNEL",
        "SUCCESS_CALLBACK,APP",
        "CONFIRM_RECEIPT,MERCHANT"
    })
    void simulatorRejectsForeignDispatchedIntentBeforeEveryMutation(
            String scenario, String mismatch) throws Exception {
        String promoter = freshPromoter(),
                d = did(promoter),
                bill = UUID.randomUUID().toString().replace("-", "");
        long identity =
                jdbc.queryForObject(
                        "select recipient_identity_id from distributor where id=?", Long.class, d);
        jdbc.update(
                "insert into"
                    + " withdrawal_request(withdrawal_no,distributor_id,amount_fen,status,out_bill_no,recipient_identity_id,recipient_cipher,app_id,merchant_id,channel,dispatch_started_at,created_at,updated_at)"
                    + " values(?,?,1000,'UNKNOWN',?,?,?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                bill,
                d,
                bill,
                identity,
                cipher.encrypt("recipient"),
                mismatch.equals("APP") ? "FOREIGN" : "DEV",
                mismatch.equals("MERCHANT") ? "FOREIGN" : "DEV-MERCHANT",
                mismatch.equals("CHANNEL") ? "WECHAT_TRANSFER" : "DEV");
        long id =
                jdbc.queryForObject(
                        "select id from withdrawal_request where out_bill_no=?", Long.class, bill);
        jdbc.update("update commission_account set withdrawing_fen=1000 where distributor_id=?", d);
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/v1/dev/withdrawals/" + id + "/simulate")
                                        .header("Authorization", "Bearer " + promoter)
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsBytes(
                                                        Map.of("scenario", scenario))))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus())
                .as(scenario + ": " + response.getContentAsString())
                .isEqualTo(503);
        assertThat(json.readTree(response.getContentAsByteArray()).path("code").asText())
                .isEqualTo("WITHDRAWAL_NOT_READY");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from transfer_simulation where withdrawal_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from withdrawal_event where withdrawal_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger where"
                                        + " withdrawal_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from withdrawal_request where id=?",
                                String.class,
                                id))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "select withdrawing_fen from commission_account where"
                                        + " distributor_id=?",
                                Long.class,
                                d))
                .isEqualTo(1000);
    }

    @Test
    void devProviderCannotSettlePersistedIntentFromAnotherChannelOrMerchant() throws Exception {
        String pa = freshPromoter(), d = did(pa);
        long identity =
                jdbc.queryForObject(
                        "select recipient_identity_id from distributor where id=?", Long.class, d);
        String bill = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "insert into"
                    + " withdrawal_request(withdrawal_no,distributor_id,amount_fen,status,out_bill_no,recipient_identity_id,recipient_cipher,app_id,merchant_id,channel,created_at,updated_at)"
                    + " values(?,?,1000,'REQUESTED',?,?,?,'REALAPP','REALMERCHANT','WECHAT_TRANSFER',UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                bill,
                d,
                bill,
                identity,
                cipher.encrypt("recipient"));
        long id =
                jdbc.queryForObject(
                        "select id from withdrawal_request where out_bill_no=?", Long.class, bill);
        jdbc.update("update commission_account set withdrawing_fen=1000 where distributor_id=?", d);
        assertThatThrownBy(() -> workflow.run(id)).isInstanceOf(TaskHandler.RetryLater.class);
        assertThat(
                        jdbc.queryForObject(
                                "select status from withdrawal_request where id=?",
                                String.class,
                                id))
                .isEqualTo("REQUESTED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from transfer_simulation where withdrawal_id=?",
                                Long.class,
                                id))
                .isZero();
    }
}
