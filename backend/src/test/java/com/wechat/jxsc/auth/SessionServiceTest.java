package com.wechat.jxsc.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wechat.jxsc.distribution.DistributorQualification;

import jakarta.persistence.EntityManager;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SessionServiceTest {
    @Test
    void customerViewUsesFallbackNicknameForLegacyAccountWithNullNickname() {
        EntityManager em = mock(EntityManager.class);
        DistributorQualification qualification = mock(DistributorQualification.class);
        UserAccount user = new UserAccount(null);
        ReflectionTestUtils.setField(user, "id", 6L);
        when(em.find(UserAccount.class, 6L)).thenReturn(user);
        when(qualification.enabled(6L)).thenReturn(false);

        SessionService sessions =
                new SessionService(
                        Clock.systemUTC(), Duration.ofDays(7), Duration.ofHours(8), qualification);
        ReflectionTestUtils.setField(sessions, "em", em);

        AuthDtos.UserView result =
                sessions.customer(
                        new CurrentActor(CurrentActor.Type.CUSTOMER, 6L, 1L, Set.of(), false));

        assertThat(result.nickname()).isEqualTo("微信用户");
    }
}
