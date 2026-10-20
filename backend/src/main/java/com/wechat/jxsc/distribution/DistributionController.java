package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/distribution")
public class DistributionController {
    private final DistributionSummaryService summary;
    private final DistributionAdministration admin;
    private final ReferralService referrals;
    private final CommissionService commissions;

    public DistributionController(
            DistributionSummaryService summary,
            DistributionAdministration admin,
            ReferralService referrals,
            CommissionService commissions) {
        this.summary = summary;
        this.admin = admin;
        this.referrals = referrals;
        this.commissions = commissions;
    }

    @GetMapping("/summary")
    public ApiResponse<FinanceDtos.Summary> summary(
            @RequestParam(defaultValue = "false") boolean optional) {
        try {
            return ApiResponse.ok(summary.summary());
        } catch (ApiException missing) {
            // The profile may check for an archive without requiring distributor qualification.
            if (optional
                    && missing.getStatus() == HttpStatus.NOT_FOUND
                    && "DISTRIBUTOR_NOT_ENABLED".equals(missing.getCode())) {
                return ApiResponse.ok(null);
            }
            throw missing;
        }
    }

    @PostMapping("/referral-codes")
    public ApiResponse<FinanceDtos.CodeView> code(@Valid @RequestBody FinanceDtos.CodeRequest r) {
        return ApiResponse.ok(referrals.code(r));
    }

    @GetMapping("/commissions")
    public ApiResponse<PageResult<FinanceDtos.CommissionView>> commissions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(
                commissions.list(FinanceStore.n(admin.own(), "id"), new PageQuery(page, pageSize)));
    }
}
