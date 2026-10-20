package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/distribution/withdrawals")
public class WithdrawalController {
    private final WithdrawalWorkflow workflow;
    private final WithdrawalTransactions transactions;

    public WithdrawalController(WithdrawalWorkflow workflow, WithdrawalTransactions transactions) {
        this.workflow = workflow;
        this.transactions = transactions;
    }

    @PostMapping
    public ApiResponse<FinanceDtos.WithdrawalView> create(
            @Valid @RequestBody FinanceDtos.WithdrawalCreate r,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(workflow.create(r, key));
    }

    @GetMapping
    public ApiResponse<PageResult<FinanceDtos.WithdrawalView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(transactions.list(true, new PageQuery(page, pageSize)));
    }

    @GetMapping("/{id}")
    public ApiResponse<FinanceDtos.WithdrawalView> get(@PathVariable String id) {
        return ApiResponse.ok(transactions.get(CommerceSupport.id(id), true));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<FinanceDtos.WithdrawalView> refresh(@PathVariable String id) {
        return ApiResponse.ok(transactions.refresh(CommerceSupport.id(id), true));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<FinanceDtos.WithdrawalView> cancel(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(transactions.requestCancel(CommerceSupport.id(id), key));
    }
}
