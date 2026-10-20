package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "jxsc.dev-seed.enabled=true")
class DistributionSeedIntegrationTest extends ContentHttpSupport {
    @Autowired ApplicationContext context;

    @Test
    void bothFixturesHaveRealIdempotentQualificationRulesAndZeroInventedMoney() throws Exception {
        for (String fixture : java.util.List.of("distributor-a", "distributor-b")) {
            var login =
                    call(
                            "POST",
                            "/auth/dev-login",
                            null,
                            java.util.Map.of("fixture", fixture),
                            200);
            assertThat(login.path("user").path("distributor").asBoolean()).isTrue();
            String token = login.path("accessToken").asText();
            var summary = call("GET", "/distribution/summary", token, null, 200);
            assertThat(summary.path("promotableProductCount").asLong()).isGreaterThanOrEqualTo(3);
            assertThat(summary.path("availableFen").asLong()).isZero();
        }
        long count =
                jdbc.queryForObject("select count(*) from product_distributor_rule", Long.class);
        Object seed = context.getBean("developmentDistributionSeed");
        seed.getClass().getMethod("initialize").invoke(seed);
        assertThat(jdbc.queryForObject("select count(*) from product_distributor_rule", Long.class))
                .isEqualTo(count);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from commission_account_ledger", Long.class))
                .isZero();
    }
}
