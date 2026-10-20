package com.wechat.jxsc.common.content;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DevelopmentSeedCondition implements Condition {
    public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
        var e = c.getEnvironment();
        return e.acceptsProfiles(Profiles.of("dev", "test"))
                && !e.acceptsProfiles(Profiles.of("prod"))
                && "DEV".equals(e.getProperty("jxsc.providers.storage"))
                && "DEV".equals(e.getProperty("jxsc.providers.identity"))
                && Boolean.parseBoolean(e.getProperty("jxsc.dev-seed.enabled", "false"));
    }
}
