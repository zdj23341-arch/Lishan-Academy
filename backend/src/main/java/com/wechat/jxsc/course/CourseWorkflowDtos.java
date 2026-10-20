package com.wechat.jxsc.course;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.catalog.CatalogDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class CourseWorkflowDtos {
    private CourseWorkflowDtos() {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowTerms")
    public record Terms(
            @NotNull @PositiveOrZero Long version,
            @NotNull CourseDtos.Validity validityType,
            @Min(1) @Max(36500) Integer validityDays,
            @Size(max = 200) String teacherName,
            @Min(0) @Max(100000) Integer durationMinutes) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowWrite")
    public record Write(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String operationKey,
            @NotNull @Valid CatalogDtos.ProductWrite product,
            @NotNull @Valid Terms course) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowPublish")
    public record Publish(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String operationKey,
            @NotNull @PositiveOrZero Long productVersion,
            @NotNull @PositiveOrZero Long courseVersion,
            @NotBlank @Size(max = 500) String reason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowChapterCreate")
    public record ChapterCreate(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String operationKey,
            @NotNull @Valid CourseDtos.ChapterWrite chapter) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowView")
    public record Workflow(String stage, CatalogDtos.AdminProduct product,
            CourseDtos.View course, List<CourseDtos.ChapterAdmin> chapters) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowChapterResult")
    public record ChapterResult(CourseDtos.ChapterAdmin chapter, Workflow workflow) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CourseWorkflowOperation")
    public record Operation(String operationKey, String action, String status,
            String courseId, JsonNode result) {}
}
