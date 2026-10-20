package com.wechat.jxsc.payment;

import java.time.Instant;

public final class EconomicDtos {
    private EconomicDtos() {}

    public record Summary(
            String orderId,
            long observedCorrectionFen,
            long goodsCorrectionFen,
            long freightCorrectionFen,
            long excessLossFen) {}

    public record Receipt(
            String id,
            String transactionId,
            long observedFen,
            long goodsFen,
            long freightFen,
            long excessLossFen,
            String actorId,
            String reason,
            String requestId,
            Instant createdAt) {}
}
