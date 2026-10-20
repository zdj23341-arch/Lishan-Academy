package com.wechat.jxsc.membership;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {
    private final MembershipViewService membership;

    public MembershipController(MembershipViewService membership) {
        this.membership = membership;
    }

    @GetMapping("/me")
    public ApiResponse<MembershipDtos.View> me() {
        return ApiResponse.ok(membership.view(EntitlementAccess.customer()));
    }
}
