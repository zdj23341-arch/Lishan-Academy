package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountClosureController {
    private final AccountClosureService service;

    public AccountClosureController(AccountClosureService service) {
        this.service = service;
    }

    public record Close(
            @NotBlank @Pattern(regexp = "CLOSE_ACCOUNT") String confirm,
            @NotNull Boolean forfeitRemainingAccess) {}

    public record Closed(String userId, String status) {}

    public record Reason(@NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/me/close")
    public ApiResponse<Closed> close(
            @Valid @RequestBody Close request, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.close(request, key));
    }

    @PostMapping("/admin/retained-personal-data/{userId}/read")
    @PreAuthorize("@actorAuthorization.hasPermission('FINANCIAL_RETENTION_READ')")
    public ApiResponse<AccountClosureService.Retained> read(
            @PathVariable long userId, @Valid @RequestBody Reason reason) {
        return ApiResponse.ok(service.retained(userId, reason.reason()));
    }
}
