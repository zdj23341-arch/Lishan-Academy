package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/membership/plans")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminMembershipController {
    private final MembershipPlanService plans;

    public AdminMembershipController(MembershipPlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public ApiResponse<PageResult<MembershipPlanService.PlanView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(plans.adminList(new PageQuery(page, pageSize)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<MembershipPlanService.PlanView> edit(
            @PathVariable long id, @Valid @RequestBody MembershipPlanService.PlanEdit r) {
        return ApiResponse.ok(plans.edit(id, r));
    }
}
