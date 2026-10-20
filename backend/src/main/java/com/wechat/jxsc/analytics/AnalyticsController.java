package com.wechat.jxsc.analytics;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@PreAuthorize("@actorAuthorization.hasPermission('ANALYTICS_READ')")
public class AnalyticsController {
    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<AnalyticsDtos.Overview> overview(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean includeDevChannel) {
        return ApiResponse.ok(service.overview(startDate, endDate, includeDevChannel));
    }

    @GetMapping("/trend")
    public ApiResponse<AnalyticsDtos.Trend> trend(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean includeDevChannel) {
        return ApiResponse.ok(service.trend(startDate, endDate, includeDevChannel));
    }
}
