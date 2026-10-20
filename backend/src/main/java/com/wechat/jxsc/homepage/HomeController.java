package com.wechat.jxsc.homepage;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
public class HomeController {
    private final HomeService home;

    public HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/api/v1/home")
    public ApiResponse<HomeDtos.View> get() {
        return ApiResponse.ok(home.published());
    }
}
