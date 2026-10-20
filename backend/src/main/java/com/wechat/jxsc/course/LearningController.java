package com.wechat.jxsc.course;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {
    private final LearningService learning;

    public LearningController(LearningService learning) {
        this.learning = learning;
    }

    @PutMapping("/chapters/{chapterId}/progress")
    public ApiResponse<LearningService.Progress> progress(
            @PathVariable long chapterId, @Valid @RequestBody LearningService.ProgressWrite body) {
        return ApiResponse.ok(learning.save(chapterId, body));
    }

    @GetMapping("/courses")
    public ApiResponse<PageResult<LearningService.CourseView>> courses(
            @io.swagger.v3.oas.annotations.Parameter(
                            description = "Optional learning grouping; omitted returns all owned courses. Response status remains entitlement status.")
                    @RequestParam(required = false) LearningFilter status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(learning.courses(new PageQuery(page, pageSize), status));
    }
}
