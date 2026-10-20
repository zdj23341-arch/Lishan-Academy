package com.wechat.jxsc.distribution;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DevTransferCondition implements Condition {
    public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
        var e = c.getEnvironment();
        return e.acceptsProfiles(Profiles.of("dev", "test"))
                && !e.acceptsProfiles(Profiles.of("prod"))
                && "DEV".equals(e.getProperty("jxsc.providers.transfer"));
    }
}
