package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.distribution.WithdrawalTransactions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("@actorAuthorization.hasPermission('FINANCE_OPERATE')")
public class EconomicResolutionController {
    private final EconomicResolutionService orders;
    private final WithdrawalTransactions withdrawals;

    public EconomicResolutionController(
            EconomicResolutionService orders, WithdrawalTransactions withdrawals) {
        this.orders = orders;
        this.withdrawals = withdrawals;
    }

    public record Resolution(@NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/orders/{id}/reconcile")
    public ApiResponse<EconomicDtos.Summary> order(
            @PathVariable long id,
            @Valid @RequestBody Resolution r,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(orders.resolve(id, r.reason(), key));
    }

    @GetMapping("/orders/{id}/economic-resolutions")
    public ApiResponse<PageResult<EconomicDtos.Receipt>> history(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(orders.history(id, new PageQuery(page, pageSize)));
    }

    @PostMapping("/withdrawals/{id}/reconcile")
    public ApiResponse<com.wechat.jxsc.distribution.FinanceDtos.WithdrawalView> withdrawal(
            @PathVariable long id,
            @Valid @RequestBody Resolution r,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(withdrawals.resolve(id, r.reason(), key));
    }
}
