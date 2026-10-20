package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/shipping-rules")
@PreAuthorize("@actorAuthorization.hasPermission('ORDER_OPERATE')")
public class AdminShippingController {
    private final ShippingRuleService rules;

    public AdminShippingController(ShippingRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public ApiResponse<PageResult<FulfillmentDtos.RuleView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(rules.list(new PageQuery(page, pageSize)));
    }

    @PutMapping("/{provinceCode}")
    public ApiResponse<FulfillmentDtos.RuleView> save(
            @PathVariable String provinceCode, @Valid @RequestBody FulfillmentDtos.RuleWrite r) {
        return ApiResponse.ok(rules.save(provinceCode, r));
    }
}
