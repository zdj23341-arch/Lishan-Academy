package com.wechat.jxsc.payment;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.*;

public final class PaymentDtos {
    public record Prepay(@NotBlank @Pattern(regexp = "[0-9]+") String orderId) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaymentView")
    public record View(String provider, String paymentId, String state, JsonNode params) {}

    public record Scenario(
            @NotBlank
                    @Pattern(
                            regexp = "SUCCESS|TIMEOUT|CLOSED|FAILURE|AMOUNT_MISMATCH|EXTRA_SUCCESS")
                    String scenario) {}
}
