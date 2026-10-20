package com.wechat.jxsc.analytics;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.distribution.FinanceStore;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.*;

class AnalyticsEnvironmentTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "prod,test", "prod,dev", "staging"})
    void productionAndUnknownProfilesRejectDevInclusionBeforeReadingData(String profiles) {
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles.split(","));
        var service = new AnalyticsService(new FinanceStore(null, Clock.systemUTC()), env);
        LocalDate day = LocalDate.parse("2026-10-08");
        assertThatThrownBy(() -> service.overview(day, day, true))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> service.trend(day, day, true))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VALIDATION_ERROR"));
    }
}
