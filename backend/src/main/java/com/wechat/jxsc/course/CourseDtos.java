package com.wechat.jxsc.course;

import jakarta.validation.constraints.*;

import java.util.*;

public final class CourseDtos {
    private CourseDtos() {}

    public enum Validity {
        DAYS,
        PERMANENT
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWrite")
    public record Write(
            @NotNull @PositiveOrZero Long version,
            @NotBlank String productId,
            @NotNull Validity validityType,
            @Min(1) @Max(36500) Integer validityDays,
            @Size(max = 200) String teacherName,
            @Min(0) @Max(100000) Integer durationMinutes) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseView")
    public record View(
            String id,
            long version,
            String productId,
            Validity validityType,
            Integer validityDays,
            String teacherName,
            int durationMinutes) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseChapterWrite")
    public record ChapterWrite(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 200) String title,
            int sortNo,
            String videoAssetId,
            @Min(1) @Max(86400) int durationSeconds,
            boolean isTrial,
            boolean enabled) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseChapterAdmin")
    public record ChapterAdmin(
            String id,
            long version,
            String courseId,
            String title,
            int sortNo,
            String videoAssetId,
            int durationSeconds,
            boolean isTrial,
            boolean enabled) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseChapterView")
    public record ChapterView(
            String id,
            String title,
            int durationSeconds,
            boolean isTrial,
            boolean canPlay,
            int lastPositionSeconds) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseChapterOrder")
    public record ChapterOrder(
            @NotNull @PositiveOrZero Long version,
            @NotNull @Size(max = 500) List<String> chapters) {}
}
