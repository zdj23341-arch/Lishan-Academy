package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminDistributionController {
    private final DistributionAdministration service;
    private final CommissionService commissions;

    public AdminDistributionController(
            DistributionAdministration service, CommissionService commissions) {
        this.service = service;
        this.commissions = commissions;
    }

    @GetMapping("/distributors")
    @PreAuthorize("@actorAuthorization.hasPermission('DISTRIBUTION_OPERATE')")
    public ApiResponse<PageResult<FinanceDtos.DistributorView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.list(new PageQuery(page, pageSize)));
    }

    @PostMapping("/distributors")
    @PreAuthorize("@actorAuthorization.hasPermission('DISTRIBUTION_OPERATE')")
    public ApiResponse<FinanceDtos.DistributorView> create(
            @Valid @RequestBody FinanceDtos.CreateDistributor r) {
        return ApiResponse.ok(service.create(r));
    }

    @PatchMapping("/distributors/{id}")
    @PreAuthorize("@actorAuthorization.hasPermission('DISTRIBUTION_OPERATE')")
    public ApiResponse<FinanceDtos.DistributorView> edit(
            @PathVariable String id, @Valid @RequestBody FinanceDtos.EditDistributor r) {
        return ApiResponse.ok(service.edit(CommerceSupport.id(id), r));
    }

    @GetMapping("/products/{id}/distributors")
    @PreAuthorize("@actorAuthorization.hasPermission('DISTRIBUTION_OPERATE')")
    public ApiResponse<FinanceDtos.RulesView> rules(@PathVariable String id) {
        return ApiResponse.ok(service.rules(CommerceSupport.id(id)));
    }

    @PutMapping("/products/{id}/distributors")
    @PreAuthorize("@actorAuthorization.hasPermission('DISTRIBUTION_OPERATE')")
    public ApiResponse<FinanceDtos.RulesView> rules(
            @PathVariable String id, @Valid @RequestBody FinanceDtos.RulesEdit r) {
        return ApiResponse.ok(service.rules(CommerceSupport.id(id), r));
    }

    @GetMapping("/commissions")
    @PreAuthorize(
            "@actorAuthorization.hasPermission('FINANCE_OPERATE') or"
                    + " @actorAuthorization.hasPermission('DISTRIBUTION_OPERATE')")
    public ApiResponse<PageResult<FinanceDtos.CommissionView>> commissions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(commissions.list(null, new PageQuery(page, pageSize)));
    }

    @GetMapping("/settings")
    @PreAuthorize("@actorAuthorization.hasPermission('SETTINGS_MANAGE')")
    public ApiResponse<FinanceDtos.SettingsView> settings() {
        return ApiResponse.ok(service.settings());
    }

    @PatchMapping("/settings")
    @PreAuthorize("@actorAuthorization.hasPermission('SETTINGS_MANAGE')")
    public ApiResponse<FinanceDtos.SettingsView> settings(
            @Valid @RequestBody FinanceDtos.SettingsEdit r) {
        return ApiResponse.ok(service.settings(r));
    }
}
