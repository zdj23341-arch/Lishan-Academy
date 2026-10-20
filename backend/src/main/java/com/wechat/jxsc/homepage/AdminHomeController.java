package com.wechat.jxsc.homepage;

import com.wechat.jxsc.common.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/home")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminHomeController {
    private final HomeService home;

    public AdminHomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/draft")
    public ApiResponse<HomeDtos.Draft> draft() {
        return ApiResponse.ok(home.draft());
    }

    @PutMapping("/draft")
    public ApiResponse<HomeDtos.Draft> save(@Valid @RequestBody HomeDtos.Draft r) {
        return ApiResponse.ok(home.save(r));
    }

    @GetMapping("/preview")
    public ApiResponse<HomeDtos.View> preview() {
        return ApiResponse.ok(home.preview());
    }

    @PostMapping("/publish")
    public ApiResponse<HomeDtos.View> publish(@Valid @RequestBody HomeDtos.Publish r) {
        return ApiResponse.ok(home.publish(r));
    }

    @PostMapping("/sections/{sectionId}/recommend")
    public ApiResponse<HomeDtos.Draft> recommend(@PathVariable String sectionId,
            @Valid @RequestBody HomeDtos.Recommendation r) {
        return ApiResponse.ok(home.recommend(sectionId, r));
    }
}
