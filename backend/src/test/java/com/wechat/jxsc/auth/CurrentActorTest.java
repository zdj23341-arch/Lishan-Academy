package com.wechat.jxsc.auth;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.ApiException;

import org.junit.jupiter.api.Test;

import java.util.Set;

class CurrentActorTest {
    @Test
    void ownershipIsEnforcedAndDoesNotTreatAdminIdsAsCustomerIds() {
        var customer = new CurrentActor(CurrentActor.Type.CUSTOMER, 12L, 1L, Set.of(), false);
        assertThatCode(() -> customer.requireOwner(12L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> customer.requireOwner(13L))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("NOT_FOUND");
        var admin = new CurrentActor(CurrentActor.Type.ADMIN, 12L, 2L, Set.of("AUDIT_READ"), false);
        assertThatThrownBy(() -> admin.requireOwner(12L))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }
}
