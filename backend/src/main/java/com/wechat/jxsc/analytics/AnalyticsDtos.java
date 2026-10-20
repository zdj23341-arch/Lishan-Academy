package com.wechat.jxsc.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.time.*;
import java.util.List;

@Schema(name = "FinanceAnalyticsDtos")
public final class AnalyticsDtos {
    private AnalyticsDtos() {}

    @Schema(name = "FinanceOverview")
    public record Overview(
            LocalDate startDate,
            LocalDate endDate,
            String timezone,
            Instant asOf,
            long paidOrders,
            long payingUsers,
            long receiptsFen,
            long refundsFen,
            long netReceiptsFen,
            long courseUnits,
            long bookUnits,
            long goodsRefundsFen,
            long returnedUnits,
            long newMembers,
            long renewalMembers,
            long newMemberReceiptsFen,
            long renewalReceiptsFen,
            long pendingShipmentUnits,
            long completedOrders,
            long noChargeOrders,
            long activeMembers,
            long downloads,
            long downloadUsers,
            long previews,
            long commissionAccruedFen,
            long commissionReversedFen,
            long availableCommissionFen,
            long pendingCommissionFen,
            long withdrawingCommissionFen,
            long paidOutFen,
            long intervalPaidOutFen,
            long debtFen,
            long financialHoldOrders,
            long unclassifiedAuthorizations,
            long orderCreatedCount,
            long waitingShipmentOrderCount,
            String environment,
            boolean includesDevChannel) {
        @JsonProperty
        public Period period() {
            return new Period(startDate, endDate, timezone);
        }

        @JsonProperty
        public Instant generatedAt() {
            return asOf;
        }

        @JsonProperty
        public PeriodMetrics periodMetrics() {
            return new PeriodMetrics(
                    orderCreatedCount,
                    paidOrders,
                    payingUsers,
                    receiptsFen,
                    refundsFen,
                    netReceiptsFen,
                    courseUnits,
                    bookUnits,
                    newMembers,
                    renewalMembers,
                    commissionAccruedFen,
                    commissionReversedFen,
                    intervalPaidOutFen);
        }

        @JsonProperty
        public CurrentMetrics currentMetrics() {
            return new CurrentMetrics(
                    activeMembers,
                    waitingShipmentOrderCount,
                    pendingCommissionFen,
                    availableCommissionFen,
                    withdrawingCommissionFen,
                    debtFen);
        }

        // Additive aliases keep the existing detailed daily report and CSV clients compatible.
        @JsonProperty
        public LocalDate date() {
            return startDate;
        }

        @JsonProperty
        public long paidOrderCount() {
            return paidOrders;
        }

        @JsonProperty
        public long receivedAmountFen() {
            return receiptsFen;
        }

        @JsonProperty
        public long refundedAmountFen() {
            return refundsFen;
        }

        @JsonProperty
        public long netCashAmountFen() {
            return netReceiptsFen;
        }
    }

    @Schema(name = "AnalyticsPeriod")
    public record Period(LocalDate startDate, LocalDate endDate, String timezone) {}

    @Schema(name = "AnalyticsPeriodMetrics")
    public record PeriodMetrics(
            long orderCreatedCount,
            long paidOrderCount,
            long paidUserCount,
            long receivedAmountFen,
            long refundedAmountFen,
            long netCashAmountFen,
            long courseSoldQuantity,
            long bookSoldQuantity,
            long membershipNewPurchaseCount,
            long membershipRenewalCount,
            long commissionEarnedFen,
            long commissionReversedFen,
            long withdrawalSucceededFen) {}

    @Schema(name = "AnalyticsCurrentMetrics")
    public record CurrentMetrics(
            long activeMemberCount,
            long waitingShipmentOrderCount,
            long pendingCommissionFen,
            long availableCommissionFen,
            long withdrawingFen,
            long debtFen) {}

    @Schema(name = "FinanceTrend")
    public record Trend(
            List<Overview> items,
            String timezone,
            Period period,
            Instant generatedAt,
            String environment,
            boolean includesDevChannel) {}

    @Schema(name = "AdminExportRequest")
    public record ExportRequest(
            @NotBlank @Pattern(regexp = "ANALYTICS|ORDERS|COMMISSIONS|WITHDRAWALS") String type,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {}

    @Schema(name = "AdminExportView")
    public record ExportView(
            String id,
            String type,
            String status,
            int rowCount,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant expiresAt,
            @Schema(types = {"string", "null"}) String downloadUrl,
            @Schema(types = {"string", "null"}) String errorCode) {}
}
