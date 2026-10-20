package com.wechat.jxsc.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class FinanceDtos {
    private FinanceDtos() {}

    @Schema(name = "DistributorCreate")
    public record CreateDistributor(
            @NotBlank String userId, @NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "DistributorEdit")
    public record EditDistributor(
            @NotNull @Min(0) Long version,
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
            @NotNull Boolean riskBlocked,
            @NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "DistributorView")
    public record DistributorView(
            String id,
            String userId,
            String status,
            boolean riskBlocked,
            boolean receiptReady,
            String reason,
            long version) {}

    @Schema(name = "DistributionRuleInput")
    public record RuleInput(
            @NotBlank String distributorId,
            @NotNull @Min(0) @Max(10000) Integer rateBps,
            @NotNull Boolean enabled,
            Instant effectiveAt,
            Instant expiresAt) {}

    @Schema(name = "DistributionRulesEdit")
    public record RulesEdit(
            @NotNull @Min(0) Long version,
            @NotNull @Size(max = 100) List<@Valid RuleInput> rules,
            @NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "DistributionRuleView")
    public record RuleView(
            String distributorId,
            int rateBps,
            boolean enabled,
            Instant effectiveAt,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant expiresAt,
            long ruleVersion) {}

    @Schema(name = "DistributionRuleSet")
    public record RulesView(long version, List<RuleView> rules) {}

    @Schema(name = "ReferralCodeCreate")
    public record CodeRequest(@NotBlank String productId) {}

    @Schema(name = "ReferralCodeView")
    public record CodeView(
            String code,
            String miniProgramPath,
            @Schema(types = {"string", "null"}) String imageUrl) {}

    @Schema(name = "ReferralResolve")
    public record ResolveRequest(
            @NotBlank @Size(max = 48) String code, @Size(max = 128) String visitorKey) {}

    @Schema(name = "ReferralClaim")
    public record ClaimRequest(@NotBlank @Size(max = 100) String attributionToken) {}

    @Schema(name = "ReferralTouchView")
    public record TouchView(String productId, String attributionToken, Instant expiresAt) {}

    @Schema(name = "WithdrawalLimits")
    public record Limits(long minFen, long maxFen, int dailyMaxCount) {}

    @Schema(name = "CommissionBalances")
    public record Balances(
            long pendingFen,
            long availableFen,
            long withdrawingFen,
            long paidOutFen,
            long debtFen) {}

    @Schema(name = "DistributionSummary")
    public record Summary(
            String distributorId,
            String status,
            boolean receiptReady,
            long pendingFen,
            long availableFen,
            long withdrawingFen,
            long paidOutFen,
            long debtFen,
            boolean withdrawalEnabled,
            @Schema(types = {"string", "null"}) String disabledReason,
            Limits limits,
            long promotableProductCount,
            long observedUnaccountedPaidOutFen) {}

    @Schema(name = "CommissionView")
    public record CommissionView(
            String id,
            String orderId,
            String distributorId,
            long baseFen,
            int rateBps,
            long earnedFen,
            long reversedFen,
            long remainingFen,
            String state,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant eligibleAt) {}

    @Schema(name = "WithdrawalCreate")
    public record WithdrawalCreate(@NotNull @Min(1) Long amountFen) {}

    @Schema(name = "TransferConfirmation")
    public record Confirmation(String packageInfo, String merchantId, String appId) {
        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "action",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String action() {
            return "WECHAT_CONFIRM_RECEIPT";
        }

        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "requestMerchantTransfer",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public MerchantTransferParameters requestMerchantTransfer() {
            return new MerchantTransferParameters(merchantId, appId, packageInfo);
        }
    }

    public record MerchantTransferParameters(
            String mchId,
            String appId,
            @com.fasterxml.jackson.annotation.JsonProperty("package") String receiptPackage) {}

    @Schema(name = "TransferReconciliationView")
    public record ReconciliationView(
            String status,
            long accountedPaidOutFen,
            long observedPaidOutFen,
            @Schema(types = {"string", "null"}) String lastQueryState) {}

    @Schema(name = "WithdrawalView")
    public record WithdrawalView(
            String id,
            String distributorId,
            long amountFen,
            @Schema(
                            allowableValues = {
                                "REQUESTED",
                                "PROCESSING",
                                "UNKNOWN",
                                "USER_ACTION_REQUIRED",
                                "SUCCEEDED",
                                "FAILED",
                                "CLOSED"
                            })
                    String status,
            String outBillNo,
            String channel,
            @Schema(types = {"string", "null"}) String providerState,
            @Schema(types = {"string", "null"}) String errorCode,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant nextQueryAt,
            boolean alerted,
            @Schema(nullable = true) Confirmation confirmation,
            Instant createdAt,
            ReconciliationView reconciliation,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant successAt) {
        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "withdrawalNo",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String withdrawalNo() {
            return outBillNo;
        }

        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "failReason",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        @Schema(types = {"string", "null"})
        public String failReason() {
            return "FAILED".equals(status) ? errorCode : null;
        }
    }

    @Schema(name = "FinanceSettingsEdit")
    public record SettingsEdit(
            @NotNull @Min(0) Long version,
            @NotNull Map<String, Long> values,
            @NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "FinanceSettingsView")
    public record SettingsView(long version, Limits limits, int observationDays) {}
}
