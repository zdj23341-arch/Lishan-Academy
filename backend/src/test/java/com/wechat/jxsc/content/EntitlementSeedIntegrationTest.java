package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "jxsc.dev-seed.enabled=true")
class EntitlementSeedIntegrationTest extends ContentHttpSupport {
    @Autowired ApplicationContext context;

    @Test
    void activeAndExpiredFixturesHaveIdempotentActualGrantsWithoutProductionOrders()
            throws Exception {
        String active = buyer("buyer-active-member"), expired = buyer("buyer-expired-member");
        assertThat(call("GET", "/membership/me", active, null, 200).path("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(call("GET", "/membership/me", expired, null, 200).path("status").asText())
                .isEqualTo("EXPIRED");
        var grants = jdbc.queryForList("select * from membership_grant order by id");
        var events = jdbc.queryForObject("select count(*) from entitlement_event", Integer.class);
        Object seed = context.getBean("developmentMembershipSeed");
        seed.getClass().getMethod("initialize").invoke(seed);
        assertThat(jdbc.queryForList("select * from membership_grant order by id"))
                .isEqualTo(grants);
        assertThat(jdbc.queryForObject("select count(*) from entitlement_event", Integer.class))
                .isEqualTo(events);
        assertThat(jdbc.queryForObject("select count(*) from store_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_transaction", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from entitlement_course_target", Integer.class))
                .isEqualTo(2);
    }
}
