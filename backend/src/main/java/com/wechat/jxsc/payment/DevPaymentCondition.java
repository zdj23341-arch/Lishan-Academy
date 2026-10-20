package com.wechat.jxsc.payment;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DevPaymentCondition implements Condition {
    public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
        var e = c.getEnvironment();
        return !e.acceptsProfiles(Profiles.of("prod"))
                && e.acceptsProfiles(Profiles.of("dev", "test"))
                && "DEV".equals(e.getProperty("jxsc.providers.payment"))
                && "DEV".equals(e.getProperty("PAYMENT_PROVIDER_MODE", "DEV"));
    }
}
