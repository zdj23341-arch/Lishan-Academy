package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final SessionService sessions;

    public AuthController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.UserView> me() {
        return ApiResponse.ok(sessions.customer(CurrentActor.require()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout() {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        sessions.revoke(actor);
        return ApiResponse.ok(null);
    }
}
