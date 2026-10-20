package com.wechat.jxsc.membership;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public final class MembershipDtos {
    public enum State {
        NONE,
        ACTIVE,
        EXPIRED
    }

    @Schema(name = "MembershipView")
    public record View(
            @Schema(allowableValues = "STANDARD") String level,
            State state,
            @Schema(
                            description =
                                    "Compatibility detail; PENDING is a future interval, never"
                                            + " active",
                            allowableValues = {"NONE", "ACTIVE", "EXPIRED", "PENDING"})
                    String status,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant startsAt,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant expiresAt,
            boolean canDownload,
            List<PlanOffer> plans) {}

    @Schema(name = "MembershipPlanOffer")
    public record PlanOffer(
            String productId,
            @Schema(allowableValues = {"6", "12"}) int durationMonths,
            long priceFen,
            Instant projectedExpiresAt) {}

    public record Record(
            String id,
            String userId,
            String level,
            String status,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant startsAt,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant expiresAt,
            long version) {}

    private MembershipDtos() {}
}
