package com.wechat.jxsc.analytics;

import com.wechat.jxsc.common.ApiResponse;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/exports")
public class ExportController {
    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<AnalyticsDtos.ExportView> create(
            @Valid @RequestBody AnalyticsDtos.ExportRequest r) {
        return ApiResponse.ok(service.create(r));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalyticsDtos.ExportView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(CommerceSupport.id(id)));
    }

    @GetMapping(value = "/{id}/content", produces = "text/csv")
    public ResponseEntity<byte[]> content(@PathVariable String id) {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Disposition", "attachment; filename=export.csv")
                .body(service.content(CommerceSupport.id(id)));
    }
}
