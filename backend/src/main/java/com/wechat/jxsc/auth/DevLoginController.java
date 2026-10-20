package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;

@RestController
@Conditional(DevIdentityCondition.class)
@RequestMapping("/api/v1/auth")
public class DevLoginController {
    private final DevFixtureService fixtures;

    public DevLoginController(DevFixtureService fixtures) {
        this.fixtures = fixtures;
    }

    @PostMapping("/dev-login")
    public ApiResponse<AuthDtos.CustomerLogin> login(
            @Valid @RequestBody AuthDtos.FixtureLogin body, HttpServletRequest request) {
        return ApiResponse.ok(fixtures.login(body.fixture(), request.getHeader("User-Agent")));
    }
}
