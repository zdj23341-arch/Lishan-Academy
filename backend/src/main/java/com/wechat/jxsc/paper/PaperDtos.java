package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.content.BodyBlocks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.*;

public final class PaperDtos {
    private PaperDtos() {}

    public enum Status {
        DRAFT,
        PUBLISHED,
        UNPUBLISHED
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperAttachmentInput")
    public record AttachmentInput(
            @NotBlank String assetId, @NotBlank @Size(max = 200) String title, int sortNo) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperWrite")
    public record Write(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 40) String stage,
            @Size(max = 40) String schoolStage,
            @NotBlank @Size(max = 20) String grade,
            @NotBlank @Size(max = 40) String subject,
            @Size(max = 40) String paperType,
            @Min(1900) @Max(2200) Integer year,
            @Size(max = 1000) String summary,
            @NotNull Status status,
            String coverAssetId,
            @NotNull @Size(max = 100) @Valid List<BodyBlocks.Input> contentBlocks,
            @NotNull @Size(max = 30) List<@Valid AttachmentInput> attachments) {
        public String resolvedSchoolStage() {
            return schoolStage == null || schoolStage.isBlank() ? stage : schoolStage;
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperSummary")
    public record Summary(
            String id,
            String title,
            String stage,
            String schoolStage,
            String grade,
            String subject,
            Integer year,
            String paperType,
            String summary,
            String coverUrl,
            List<String> attachmentFormats,
            String accessCondition,
            String resourceStatus) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperAttachment")
    public record Attachment(
            String id,
            String name,
            String extension,
            String mime,
            long bytes,
            String title,
            boolean canPreview,
            boolean canDownload) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperView")
    public record View(
            String id,
            String title,
            String stage,
            String schoolStage,
            String grade,
            String subject,
            Integer year,
            String paperType,
            String summary,
            String visibility,
            String coverUrl,
            List<BodyBlocks.View> contentBlocks,
            List<Attachment> attachments,
            String accessCondition,
            String resourceStatus) {}

    /** One deduplicated row per paper file the customer actually opened, newest first. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperDownload")
    public record Download(
            String paperId,
            String paperTitle,
            String paperStatus,
            String fileId,
            String fileName,
            String extension,
            String mime,
            long bytes,
            String action,
            Instant lastAt,
            int times,
            boolean canDownload,
            String unavailableReason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaperAdmin")
    public record Admin(
            String id,
            long version,
            String title,
            String stage,
            String schoolStage,
            String grade,
            String subject,
            Integer year,
            String paperType,
            String summary,
            Status status,
            String coverAssetId,
            List<BodyBlocks.Input> contentBlocks,
            List<AttachmentInput> attachments) {}
}
