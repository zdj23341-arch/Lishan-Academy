package com.wechat.jxsc.article;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {
    private final ArticleService articles;

    public ArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @GetMapping("/{id}")
    public ApiResponse<ArticleDtos.View> get(@PathVariable long id) {
        return ApiResponse.ok(articles.detail(id));
    }
}
