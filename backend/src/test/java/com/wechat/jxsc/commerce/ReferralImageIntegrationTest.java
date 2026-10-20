package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.wechat.jxsc.distribution.WechatMiniProgramCodeClient;
import com.wechat.jxsc.payment.wechat.WechatProtocol;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

@TestPropertySource(properties = "jxsc.wechat.share.enabled=true")
class ReferralImageIntegrationTest extends DistributionTestSupport {
    @MockitoBean WechatMiniProgramCodeClient client;
    // Valid 1x1 PNG; only the external WeChat collaborator is substituted.
    private static final byte[] PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");

    @Test
    void cachesImageAcrossRequestsAndRejectsExpiredOrDisabledReferrals() throws Exception {
        String promoter = freshPromoter(), pid = book(a, 10, 2000);
        rule(pid, did(promoter));
        when(client.generate(anyString()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(inv.<String>getArgument(0))
                                    .startsWith("pages/product/detail?id=" + pid + "&scene=");
                            return PNG;
                        });
        var first = write("/distribution/referral-codes", promoter, Map.of("productId", pid));
        String code = first.path("code").asText();
        String url = first.path("imageUrl").asText();
        assertThat(url).isEqualTo("/api/v1/referrals/" + code + "/image");
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
        var second = write("/distribution/referral-codes", promoter, Map.of("productId", pid));
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).generate(anyString());
        jdbc.update("update referral_code set enabled=false where code=?", code);
        mvc.perform(get(url)).andExpect(status().isNotFound());
        var third = write("/distribution/referral-codes", promoter, Map.of("productId", pid));
        assertThat(third.path("code").asText()).isNotEqualTo(code);
        jdbc.update(
                "update referral_code set expires_at=? where code=?",
                java.sql.Timestamp.from(clock.now.minusSeconds(1)),
                third.path("code").asText());
        mvc.perform(get(third.path("imageUrl").asText())).andExpect(status().isNotFound());
        verify(client, times(2)).generate(anyString());
    }

    @Test
    void unauthorizedRequestsSpendNoQuotaAndProviderFailureIsRetryable() throws Exception {
        String promoter = freshPromoter(), pid = book(a, 10, 2000);
        write(
                "POST",
                "/distribution/referral-codes",
                promoter,
                Map.of("productId", pid),
                UUID.randomUUID().toString(),
                400);
        verifyNoInteractions(client);
        rule(pid, did(promoter));
        when(client.generate(anyString()))
                .thenThrow(WechatProtocol.failure("WECHAT_SHARE_REJECTED"));
        write(
                "POST",
                "/distribution/referral-codes",
                promoter,
                Map.of("productId", pid),
                UUID.randomUUID().toString(),
                502);
        doReturn(PNG).when(client).generate(anyString());
        assertThat(
                        write("/distribution/referral-codes", promoter, Map.of("productId", pid))
                                .path("imageUrl")
                                .isTextual())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from referral_code where product_id=?",
                                Long.class,
                                pid))
                .isEqualTo(1);
    }

    @Test
    void generationLeaseSurvivesCrashAndEligibilityIsCheckedAgainAfterIo() throws Exception {
        String promoter = freshPromoter(), pid = book(a, 10, 2000);
        rule(pid, did(promoter));
        when(client.generate(anyString()))
                .thenThrow(WechatProtocol.failure("WECHAT_SHARE_REJECTED"));
        write(
                "POST",
                "/distribution/referral-codes",
                promoter,
                Map.of("productId", pid),
                UUID.randomUUID().toString(),
                502);
        jdbc.update(
                "update referral_code set image_lease_token='crashed',image_lease_until=? where"
                        + " product_id=?",
                java.sql.Timestamp.from(clock.now.plusSeconds(120)),
                pid);
        write(
                "POST",
                "/distribution/referral-codes",
                promoter,
                Map.of("productId", pid),
                UUID.randomUUID().toString(),
                503);
        verify(client, times(1)).generate(anyString());
        clock.now = clock.now.plusSeconds(121);
        doAnswer(
                        inv -> {
                            jdbc.update(
                                    "update distributor set status='DISABLED' where id=?",
                                    did(promoter));
                            return PNG;
                        })
                .when(client)
                .generate(anyString());
        write(
                "POST",
                "/distribution/referral-codes",
                promoter,
                Map.of("productId", pid),
                UUID.randomUUID().toString(),
                400);
        String code =
                jdbc.queryForObject(
                        "select code from referral_code where product_id=?", String.class, pid);
        mvc.perform(get("/api/v1/referrals/" + code + "/image")).andExpect(status().isBadRequest());
    }
}
