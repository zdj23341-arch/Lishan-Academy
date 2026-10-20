package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiResponse;
import com.wechat.jxsc.common.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuthController {
    private final SessionService sessions;
    private final AdminAuthService admins;
    private final LoginRateLimiter limiter;

    public AdminAuthController(
            SessionService sessions, AdminAuthService admins, LoginRateLimiter limiter) {
        this.sessions = sessions;
        this.admins = admins;
        this.limiter = limiter;
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthDtos.AdminLoginResult> login(
            @Valid @RequestBody AuthDtos.AdminLogin body, HttpServletRequest request) {
        limiter.check(body.canonicalLoginName(), request.getRemoteAddr());
        return ApiResponse.ok(
                admins.login(
                        body, request.getHeader("User-Agent"), RequestIdFilter.requestId(request)));
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.AdminView> me() {
        return ApiResponse.ok(sessions.admin(CurrentActor.require()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        admins.logout(CurrentActor.require(), RequestIdFilter.requestId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/change-password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody AuthDtos.PasswordChange body, HttpServletRequest request) {
        var actor = CurrentActor.require();
        limiter.check("password-change:" + actor.id(), request.getRemoteAddr());
        admins.changePassword(actor, body, RequestIdFilter.requestId(request));
        return ApiResponse.ok(null);
    }
}
