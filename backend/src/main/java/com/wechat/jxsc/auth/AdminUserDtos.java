package com.wechat.jxsc.auth;

import com.wechat.jxsc.membership.EntitlementAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AdminUserDtos {
    public enum Status { NORMAL, BLOCKED, CLOSED }
    public enum WritableStatus { NORMAL, BLOCKED }

    @Schema(name = "AdminUserStatusWrite")
    public record StatusWrite(@NotNull WritableStatus status,
            @NotBlank @Size(max = 500) String reason, @NotNull @PositiveOrZero Long version) {}

    @Schema(name = "AdminCustomerView")
    public record View(String id,
            @Schema(description = "No customer username exists", types = {"string", "null"}) String username,
            String nickname,
            @Schema(description = "Not exposed by this directory", types = {"string", "null"}) String phone,
            Status status, Instant registeredAt,
            @Schema(types = {"string", "null"}, format = "date-time") Instant lastLoginAt,
            long version, EntitlementAccess.Right membership) {}
    private AdminUserDtos() {}
}
