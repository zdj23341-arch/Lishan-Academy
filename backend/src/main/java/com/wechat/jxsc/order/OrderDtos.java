package com.wechat.jxsc.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.fulfillment.FulfillmentDtos;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class OrderDtos {
    public record Preview(
            @NotBlank @Pattern(regexp = "[0-9]+") String productId,
            @NotNull @Min(1) @Max(99) Integer quantity,
            @Pattern(regexp = "[0-9]+") String addressId) {}

    public record Create(
            @NotBlank @Pattern(regexp = "[0-9]+") String quoteId,
            @Size(max = 500) String attributionToken) {}

    public record Item(
            String id,
            String productId,
            String type,
            String title,
            int quantity,
            int restoredQuantity,
            int fulfillableQuantity,
            int shippedQuantity,
            long unitPriceFen,
            JsonNode fulfillmentSnapshot) {
        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "coverUrl",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
        public String coverUrl() {
            return fulfillmentSnapshot == null
                    ? null
                    : fulfillmentSnapshot.path("coverUrl").asText(null);
        }
    }

    public record Quote(
            String quoteId,
            Instant expiresAt,
            Item item,
            int availableStock,
            long goodsAmountFen,
            long freightAmountFen,
            long payableAmountFen,
            String currency,
            List<String> warnings) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrderView")
    public record View(
            String id,
            String orderNo,
            long version,
            String orderStatus,
            String refundStatus,
            String fulfillmentStatus,
            String channel,
            Item item,
            long goodsAmountFen,
            long freightAmountFen,
            long payableAmountFen,
            long paidAmountFen,
            long refundedGoodsFen,
            long refundedFreightFen,
            String currency,
            Instant expiresAt,
            Instant paidAt,
            Instant completedAt,
            FulfillmentDtos.AddressView address,
            boolean financialHold,
            com.wechat.jxsc.payment.ReconciliationDtos.View reconciliation,
            List<String> allowedActions) {
        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "refundedAmountFen",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public long refundedAmountFen() {
            return reconciliation == null
                    ? 0
                    : Math.addExact(
                            reconciliation.confirmedRefundFen(),
                            reconciliation.confirmedExceptionalRefundFen());
        }
    }

    public record ApplyRefund(
            @NotBlank @Pattern(regexp = "REFUND_ONLY|RETURN_REFUND") String type,
            @NotBlank @Size(max = 500) String reason,
            @NotNull @Size(max = 10) List<@Pattern(regexp = "[0-9]+") String> evidenceAssetIds,
            @NotNull @Min(0) @Max(10000000000L) Long requestedGoodsFen,
            @NotNull @Min(0) @Max(100000000) Long requestedFreightFen) {}

    public record Decision(
            @com.fasterxml.jackson.annotation.JsonProperty("goodsRefundFen")
                    @NotNull
                    @Min(0)
                    @Max(10000000000L)
                    Long goodsFen,
            @com.fasterxml.jackson.annotation.JsonProperty("freightRefundFen")
                    @NotNull
                    @Min(0)
                    @Max(100000000)
                    Long freightFen,
            @NotNull @Min(0) @Max(99) Integer returnedQuantity,
            @Size(max = 1000) String returnAddress,
            @NotBlank @Size(max = 500) String reason) {}

    public record Reason(@NotBlank @Size(max = 500) String reason) {}

    public record AftersaleView(
            String id,
            String orderId,
            String type,
            String status,
            String reason,
            long requestedGoodsFen,
            long requestedFreightFen,
            Long approvedGoodsFen,
            Long approvedFreightFen,
            int returnedQuantity,
            int receivedQuantity,
            String returnAddress,
            String returnCarrierCode,
            String returnTrackingNo,
            List<String> evidenceAssetIds,
            List<String> allowedActions) {}

    public record DecisionPreview(
            long goodsFen,
            long freightFen,
            int returnedQuantity,
            boolean fullRefund,
            String entitlementEffect,
            String commissionEffect,
            JsonNode consumerPreview) {}
}
