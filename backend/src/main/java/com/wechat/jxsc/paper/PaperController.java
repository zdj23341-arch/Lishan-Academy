package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {
    private final PaperService papers;

    public PaperController(PaperService papers) {
        this.papers = papers;
    }

    @GetMapping
    public ApiResponse<PageResult<PaperDtos.Summary>> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String schoolStage,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String paperType,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(
                papers.list(
                        keyword,
                        schoolStage == null ? stage : schoolStage,
                        grade,
                        year,
                        subject,
                        paperType == null ? type : paperType,
                        new PageQuery(page, pageSize)));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperDtos.View> get(@PathVariable long id) {
        return ApiResponse.ok(papers.detail(id));
    }
}
