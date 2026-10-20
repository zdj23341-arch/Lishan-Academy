package com.wechat.jxsc.course;

import com.wechat.jxsc.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/course-workflows")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class CourseWorkflowController {
    private final CourseWorkflowService workflows;

    public CourseWorkflowController(CourseWorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.Operation(summary = "Create one recoverable linked course draft",
            description = "Persist the UUID operationKey before sending. Same actor/key/payload replays the original result; changed payload returns 409 IDEMPOTENCY_CONFLICT.")
    public ApiResponse<CourseWorkflowDtos.Workflow> create(@Valid @RequestBody CourseWorkflowDtos.Write request) {
        return ApiResponse.ok(workflows.save(null, request));
    }

    @GetMapping("/{courseId}")
    public ApiResponse<CourseWorkflowDtos.Workflow> get(@PathVariable long courseId) {
        return ApiResponse.ok(workflows.get(courseId));
    }

    @PutMapping("/{courseId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atomically save linked product and course",
            description = "Requires independent current product.version and course.version. Immutable product linkage is derived from courseId.")
    public ApiResponse<CourseWorkflowDtos.Workflow> save(@PathVariable long courseId,
            @Valid @RequestBody CourseWorkflowDtos.Write request) {
        return ApiResponse.ok(workflows.save(courseId, request));
    }

    @PostMapping("/{courseId}/publish")
    public ApiResponse<CourseWorkflowDtos.Workflow> publish(@PathVariable long courseId,
            @Valid @RequestBody CourseWorkflowDtos.Publish request) {
        return ApiResponse.ok(workflows.publish(courseId, request));
    }

    @PostMapping("/{courseId}/chapters")
    public ApiResponse<CourseWorkflowDtos.ChapterResult> chapter(@PathVariable long courseId,
            @Valid @RequestBody CourseWorkflowDtos.ChapterCreate request) {
        return ApiResponse.ok(workflows.chapter(courseId, request));
    }

    @GetMapping("/operations/{operationKey}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Recover an unknown workflow mutation result",
            description = "Actor-scoped permanent COMMITTED receipt. 404 means no committed receipt is visible yet; retry the original payload and key, never create a fresh key for an unknown outcome.")
    public ApiResponse<CourseWorkflowDtos.Operation> operation(@PathVariable String operationKey) {
        return ApiResponse.ok(workflows.operation(operationKey));
    }
}
