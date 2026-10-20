package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/withdrawals")
@PreAuthorize("@actorAuthorization.hasPermission('FINANCE_OPERATE')")
public class AdminWithdrawalController {
    private final WithdrawalTransactions transactions;

    public AdminWithdrawalController(WithdrawalTransactions transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    public ApiResponse<PageResult<FinanceDtos.WithdrawalView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(transactions.list(false, new PageQuery(page, pageSize)));
    }

    @GetMapping("/{id}")
    public ApiResponse<FinanceDtos.WithdrawalView> get(@PathVariable String id) {
        return ApiResponse.ok(transactions.get(CommerceSupport.id(id), false));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<FinanceDtos.WithdrawalView> refresh(@PathVariable String id) {
        return ApiResponse.ok(transactions.refresh(CommerceSupport.id(id), false));
    }
}
