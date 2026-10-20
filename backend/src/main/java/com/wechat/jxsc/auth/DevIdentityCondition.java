package com.wechat.jxsc.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DevIdentityCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        return !env.acceptsProfiles(Profiles.of("prod"))
                && env.acceptsProfiles(Profiles.of("dev", "test"))
                && "DEV".equals(env.getProperty("jxsc.providers.identity"))
                && "DEV".equals(env.getProperty("IDENTITY_PROVIDER_MODE", "DEV"));
    }
}
