package com.wechat.jxsc.auth;

import org.springframework.stereotype.Component;

@Component("actorAuthorization")
public class Authorization {
    public boolean hasPermission(String permission) {
        var actor = CurrentActor.require();
        return actor.type() == CurrentActor.Type.ADMIN
                && !actor.passwordChangeRequired()
                && actor.permissions().contains(permission);
    }
}
