package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class WithdrawalIntegrationTest extends DistributionTestSupport {
    String funded() throws Exception {
        String token = freshPromoter();
        String d = did(token);
        // Isolated balance fixture at the module boundary, no invented channel success.
        jdbc.update(
                "update commission_account set"
                    + " available_fen=10000,pending_fen=0,debt_fen=0,withdrawing_fen=0,paid_out_fen=0"
                    + " where distributor_id=?",
                d);
        return token;
    }

    @Test
    void concurrentEightThousandWithdrawalsFreezeOnceAndReplayOriginal() throws Exception {
        String p = funded();
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        List<Future<JsonNode>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++)
            results.add(
                    pool.submit(
                            () -> {
                                gate.await();
                                var res =
                                        mvc.perform(
                                                        org.springframework.test.web.servlet.request
                                                                .MockMvcRequestBuilders.post(
                                                                        "/api/v1/distribution/withdrawals")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + p)
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        UUID.randomUUID()
                                                                                .toString())
                                                                .contentType("application/json")
                                                                .content("{\"amountFen\":8000}"))
                                                .andReturn()
                                                .getResponse();
                                return json.readTree(res.getContentAsByteArray());
                            }));
        gate.countDown();
        int success = 0;
        for (var result : results)
            if (result.get(10, TimeUnit.SECONDS).path("code").asText().equals("OK")) success++;
        pool.shutdownNow();
        assertThat(success).isEqualTo(1);
        var s = call("GET", "/distribution/summary", p, null, 200);
        assertThat(s.path("availableFen").asLong()).isEqualTo(2000);
        assertThat(s.path("withdrawingFen").asLong()).isEqualTo(8000);
    }

    @Test
    void timeoutKeepsFundsThenSuccessCannotBeDowngradedAndPackageIsOwnerOnly() throws Exception {
        String p = funded(), key = UUID.randomUUID().toString();
        var w = write("POST", "/distribution/withdrawals", p, Map.of("amountFen", 8000), key, 200);
        String id = w.path("id").asText();
        assertThat(w.path("withdrawalNo")).isEqualTo(w.path("outBillNo"));
        assertThat(w.path("successAt").isNull()).isTrue();
        assertThat(w.path("failReason").isNull()).isTrue();
        assertThat(w.path("outBillNo").asText()).matches("[A-Za-z0-9]{1,32}");
        assertThat(
                        write(
                                        "POST",
                                        "/distribution/withdrawals",
                                        p,
                                        Map.of("amountFen", 8000),
                                        key,
                                        200)
                                .path("id")
                                .asText())
                .isEqualTo(id);
        write("POST", "/distribution/withdrawals", p, Map.of("amountFen", 7000), key, 409);
        write("/dev/withdrawals/" + id + "/simulate", p, Map.of("scenario", "TIMEOUT"));
        assertThat(
                        call("GET", "/distribution/withdrawals/" + id, p, null, 200)
                                .path("status")
                                .asText())
                .isEqualTo("UNKNOWN");
        assertThat(
                        call("GET", "/distribution/summary", p, null, 200)
                                .path("withdrawingFen")
                                .asLong())
                .isEqualTo(8000);
        write("/dev/withdrawals/" + id + "/simulate", p, Map.of("scenario", "SUCCESS_CALLBACK"));
        var successAt =
                call("GET", "/distribution/withdrawals/" + id, p, null, 200).path("successAt");
        assertThat(successAt.isTextual()).isTrue();
        clock.now = clock.now.plusSeconds(60);
        write("/dev/withdrawals/" + id + "/simulate", p, Map.of("scenario", "FAILURE_CALLBACK"));
        assertThat(call("GET", "/distribution/withdrawals/" + id, p, null, 200).path("successAt"))
                .isEqualTo(successAt);
        assertThat(
                        call("GET", "/distribution/withdrawals/" + id, p, null, 200)
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        assertThat(call("GET", "/distribution/summary", p, null, 200).path("paidOutFen").asLong())
                .isEqualTo(8000);
        call("GET", "/distribution/withdrawals/" + id, b, null, 404);
    }
}
