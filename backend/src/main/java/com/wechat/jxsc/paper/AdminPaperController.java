package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/papers")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminPaperController {
    private final PaperService papers;

    public AdminPaperController(PaperService papers) {
        this.papers = papers;
    }

    @GetMapping
    public ApiResponse<PageResult<PaperDtos.Admin>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String schoolStage,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String paperType,
            @RequestParam(required = false) PaperDtos.Status status) {
        return ApiResponse.ok(
                papers.adminList(
                        new PageQuery(page, pageSize),
                        keyword,
                        schoolStage == null ? stage : schoolStage,
                        grade,
                        subject,
                        year,
                        paperType,
                        status));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperDtos.Admin> get(@PathVariable long id) {
        return ApiResponse.ok(papers.adminGet(id));
    }

    @PostMapping
    public ApiResponse<PaperDtos.Admin> create(@Valid @RequestBody PaperDtos.Write r) {
        return ApiResponse.ok(papers.save(null, r));
    }

    @PatchMapping("/{id}")
    public ApiResponse<PaperDtos.Admin> edit(
            @PathVariable long id, @Valid @RequestBody PaperDtos.Write r) {
        return ApiResponse.ok(papers.save(id, r));
    }
}
