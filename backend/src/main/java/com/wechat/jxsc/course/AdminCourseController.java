package com.wechat.jxsc.course;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminCourseController {
    private final CourseService courses;

    public AdminCourseController(CourseService courses) {
        this.courses = courses;
    }

    @GetMapping("/courses")
    public ApiResponse<PageResult<CourseDtos.View>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(courses.list(new PageQuery(page, pageSize)));
    }

    @GetMapping("/courses/{id}")
    public ApiResponse<CourseDtos.View> get(@PathVariable long id) {
        return ApiResponse.ok(courses.get(id));
    }

    @PostMapping("/courses")
    public ApiResponse<CourseDtos.View> create(@Valid @RequestBody CourseDtos.Write r) {
        return ApiResponse.ok(courses.save(null, r));
    }

    @PatchMapping("/courses/{id}")
    public ApiResponse<CourseDtos.View> edit(
            @PathVariable long id, @Valid @RequestBody CourseDtos.Write r) {
        return ApiResponse.ok(courses.save(id, r));
    }

    @GetMapping("/courses/{id}/chapters")
    public ApiResponse<PageResult<CourseDtos.ChapterAdmin>> chapters(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(courses.adminChapters(id, new PageQuery(page, pageSize)));
    }

    @PostMapping("/courses/{id}/chapters")
    public ApiResponse<CourseDtos.ChapterAdmin> chapter(
            @PathVariable long id, @Valid @RequestBody CourseDtos.ChapterWrite r) {
        return ApiResponse.ok(courses.chapter(null, id, r));
    }

    @PatchMapping("/chapters/{id}")
    public ApiResponse<CourseDtos.ChapterAdmin> editChapter(
            @PathVariable long id, @Valid @RequestBody CourseDtos.ChapterWrite r) {
        return ApiResponse.ok(courses.editChapter(id, r));
    }

    @PutMapping("/courses/{id}/chapter-order")
    public ApiResponse<CourseDtos.View> order(
            @PathVariable long id, @Valid @RequestBody CourseDtos.ChapterOrder r) {
        return ApiResponse.ok(courses.order(id, r));
    }
}
