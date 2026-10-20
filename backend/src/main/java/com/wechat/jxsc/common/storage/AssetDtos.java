package com.wechat.jxsc.common.storage;

import jakarta.validation.constraints.*;

import java.time.Instant;

public final class AssetDtos {
    private AssetDtos() {}

    public enum Purpose {
        PUBLIC_IMAGE,
        PAPER_ATTACHMENT,
        COURSE_VIDEO,
        AFTERSALE_EVIDENCE
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetPresign")
    public record Presign(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String mime,
            @Positive long bytes,
            @NotNull Purpose purpose) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetTicket")
    public record Ticket(String id, String uploadUrl, String uploadToken, Instant expiresAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetCompletion")
    public record Completion() {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetDisable")
    public record Disable(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "DISABLED") String status,
            @NotBlank @Size(max = 500) String reason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetAdminView")
    public record AdminView(
            String id,
            long version,
            String name,
            String extension,
            String mime,
            Long bytes,
            String sha256,
            String purpose,
            String status,
            String scanStatus,
            String publicUrl) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetView")
    public record View(
            String id,
            String name,
            String extension,
            String mime,
            Long bytes,
            String sha256,
            String purpose,
            String status,
            String scanStatus,
            String publicUrl) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetSummary")
    public record Summary(String id, String name, String extension, String mime, long bytes) {}
}
