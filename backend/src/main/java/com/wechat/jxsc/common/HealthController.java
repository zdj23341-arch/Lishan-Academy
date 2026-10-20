package com.wechat.jxsc.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public ApiResponse<Health> health() {
        return ApiResponse.ok(new Health("UP"));
    }

    public record Health(String status) {}
}
