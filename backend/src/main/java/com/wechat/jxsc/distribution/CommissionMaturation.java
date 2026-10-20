package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.tasks.TaskHandler;

import org.springframework.stereotype.Component;

@Component
public class CommissionMaturation implements TaskHandler {
    private final CommissionService commissions;

    public CommissionMaturation(CommissionService commissions) {
        this.commissions = commissions;
    }

    public boolean supports(String type) {
        return type.equals("COMMISSION_MATURE");
    }

    public void handle(String type, String key, String payload) {
        commissions.mature(Long.parseLong(key));
    }
}
