package com.wechat.jxsc.fulfillment;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class FulfillmentDtos {
    public record AddressWrite(
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[+0-9 -]{6,30}") String phone,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String provinceCode,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String cityCode,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String districtCode,
            @NotBlank @Size(max = 500) String detail,
            @NotNull Boolean isDefault) {}

    public record AddressView(
            String id,
            long version,
            String name,
            String phone,
            String provinceCode,
            String cityCode,
            String districtCode,
            String detail,
            boolean isDefault) {}

    public record RuleWrite(
            @NotNull @Min(0) Long version,
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(100000000) Long freightFen,
            @Min(0) @Max(10000000000L) Long freeThresholdFen,
            @NotBlank @Size(max = 500) String reason) {}

    public record RuleView(
            String id,
            long version,
            String provinceCode,
            boolean enabled,
            long freightFen,
            Long freeThresholdFen) {}

    public record Ship(
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 40) String carrierCode,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,100}") String trackingNo,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReturnShipment(
            @NotBlank @Size(max = 40) String carrierCode,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,100}") String trackingNo) {}

    public record ReceiveReturn(
            @com.fasterxml.jackson.annotation.JsonProperty("returnedQuantity")
                    @NotNull
                    @Min(0)
                    @Max(99)
                    Integer receivedQuantity,
            @NotBlank @Size(max = 500) String reason) {}

    public record Event(Instant occurredAt, String description) {}

    public record LogisticsView(
            int shippedQuantity,
            String carrierCode,
            String trackingNo,
            String status,
            boolean simulated,
            Instant shippedAt,
            Instant receivedAt,
            List<Event> events) {}
}
