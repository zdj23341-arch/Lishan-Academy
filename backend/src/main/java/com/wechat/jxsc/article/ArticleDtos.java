package com.wechat.jxsc.article;

import com.wechat.jxsc.common.content.BodyBlocks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.*;

public final class ArticleDtos {
    private ArticleDtos() {}

    public enum Status {
        DRAFT,
        PUBLISHED,
        UNPUBLISHED
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ArticleWrite")
    public record Write(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 200) String title,
            String coverAssetId,
            @NotNull Status status,
            @NotNull @Size(max = 100) @Valid List<BodyBlocks.Input> contentBlocks,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Optional author; omitted/null preserves on edit, blank clears", types = {"string", "null"})
            @Size(max = 100) String authorName,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Single free-text article category; omitted/null preserves on edit, blank clears", types = {"string", "null"})
            @Size(max = 80) String categoryName) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ArticleSummary")
    public record Summary(String id, String title, String coverUrl) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ArticleView")
    public record View(
            String id,
            String title,
            String coverUrl,
            List<BodyBlocks.View> contentBlocks,
            Instant publishedAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String authorName,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String categoryName,
            Instant updatedAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ArticleAdmin")
    public record Admin(
            String id,
            long version,
            String title,
            String coverAssetId,
            Status status,
            List<BodyBlocks.Input> contentBlocks,
            Instant publishedAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String authorName,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String categoryName,
            Instant updatedAt) {}
}
