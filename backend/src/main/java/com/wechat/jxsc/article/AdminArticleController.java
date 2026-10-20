package com.wechat.jxsc.article;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/articles")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminArticleController {
    private final ArticleService articles;

    public AdminArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @GetMapping
    public ApiResponse<PageResult<ArticleDtos.Admin>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ArticleDtos.Status status) {
        return ApiResponse.ok(articles.list(new PageQuery(page, pageSize), keyword, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<ArticleDtos.Admin> get(@PathVariable long id) {
        return ApiResponse.ok(articles.adminGet(id));
    }

    @PostMapping
    public ApiResponse<ArticleDtos.Admin> create(@Valid @RequestBody ArticleDtos.Write r) {
        return ApiResponse.ok(articles.save(null, r));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ArticleDtos.Admin> edit(
            @PathVariable long id, @Valid @RequestBody ArticleDtos.Write r) {
        return ApiResponse.ok(articles.save(id, r));
    }
}
