package com.wechat.jxsc.auth.wechat;

import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class WechatLoginController {
    private final WechatLoginService login;
    private final LoginRateLimiter limits;

    public WechatLoginController(WechatLoginService login, LoginRateLimiter limits) {
        this.login = login;
        this.limits = limits;
    }

    public record Login(@NotBlank @Size(max = 256) String code) {
        @Override
        public String toString() {
            return "WechatLogin[redacted]";
        }
    }

    @PostMapping("/wechat-login")
    public ApiResponse<AuthDtos.CustomerLogin> login(
            @Valid @RequestBody Login request, HttpServletRequest servlet) {
        limits.customer(servlet.getRemoteAddr());
        return ApiResponse.ok(login.login(request.code(), servlet.getHeader("User-Agent")));
    }
}
