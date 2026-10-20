package com.wechat.jxsc.course;

import com.wechat.jxsc.common.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {
    private final CourseService courses;

    public CourseController(CourseService courses) {
        this.courses = courses;
    }

    @GetMapping("/{id}/chapters")
    public ApiResponse<PageResult<CourseDtos.ChapterView>> chapters(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(courses.publicChapters(id, new PageQuery(page, pageSize)));
    }
}
