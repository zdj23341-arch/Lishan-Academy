package com.wechat.jxsc.homepage;

import com.wechat.jxsc.article.ArticleDtos;
import com.wechat.jxsc.catalog.CatalogDtos;
import com.wechat.jxsc.paper.PaperDtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

public final class HomeDtos {
    private HomeDtos() {}

    public enum TargetType {
        PRODUCT,
        PRODUCT_LIST,
        MEMBERSHIP,
        PAPER_LIST,
        ARTICLE,
        LEARNING,
        ORDER_LIST,
        LOGISTICS,
        MEMBERSHIP_CENTER,
        COURSE_DETAIL
    }

    public enum SectionType {
        PRODUCT_GRID,
        BOOK_GRID,
        COURSE_GRID,
        PAPER_GRID,
        MEMBERSHIP_ENTRY,
        ARTICLE_CARD
    }

    public enum SelectionMode {
        MANUAL,
        AUTO
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeTarget")
    public record Target(
            @NotNull TargetType type,
            String id,
            @NotNull @Size(max = 5) Map<String, String> filters) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeBannerInput")
    public record BannerInput(
            @NotBlank String id,
            @NotBlank @Size(max = 200) String title,
            @NotBlank String assetId,
            int sortNo,
            boolean enabled,
            @NotNull @Valid Target target) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeShortcutInput")
    public record ShortcutInput(
            @NotBlank String id,
            @NotBlank @Size(max = 200) String label,
            @NotBlank String assetId,
            int sortNo,
            boolean enabled,
            @NotNull @Valid Target target) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeSectionInput")
    public record SectionInput(
            @NotBlank String id,
            @NotNull SectionType type,
            @NotBlank @Size(max = 200) String title,
            int sortNo,
            boolean enabled,
            @Valid Target moreTarget,
            @NotNull @Size(max = 50) List<String> productIds,
            String articleId,
            @Size(max = 50) List<String> paperIds,
            SelectionMode selectionMode,
            @Min(1) @Max(50) Integer displayLimit,
            @Size(max = 50) List<@NotBlank String> pinnedIds) {
        public SectionInput {
            selectionMode = selectionMode == null ? SelectionMode.MANUAL : selectionMode;
            displayLimit = displayLimit == null ? 4 : displayLimit;
            pinnedIds = pinnedIds == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(pinnedIds));
        }
        public SectionInput(String id, SectionType type, String title, int sortNo,
                boolean enabled, Target moreTarget, List<String> productIds, String articleId,
                List<String> paperIds) {
            this(id, type, title, sortNo, enabled, moreTarget, productIds, articleId, paperIds,
                    SelectionMode.MANUAL, 4, List.of());
        }
        public SectionInput(
                String id,
                SectionType type,
                String title,
                int sortNo,
                boolean enabled,
                Target moreTarget,
                List<String> productIds,
                String articleId) {
            this(id, type, title, sortNo, enabled, moreTarget, productIds, articleId, List.of(),
                    SelectionMode.MANUAL, 4, List.of());
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeDraft")
    public record Draft(
            @PositiveOrZero long revision,
            @NotNull @Size(max = 20) List<@Valid BannerInput> banners,
            @NotNull @Size(max = 30) List<@Valid ShortcutInput> shortcuts,
            @NotNull @Size(max = 20) List<@Valid SectionInput> sections) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomePublish")
    public record Publish(
            @PositiveOrZero long revision, @NotBlank @Size(max = 500) String reason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeBanner")
    public record Banner(String id, String imageUrl, String title, Target target) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeShortcut")
    public record Shortcut(String id, String label, String iconUrl, Target target) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeSection")
    public record Section(
            String id,
            SectionType type,
            String title,
            Target moreTarget,
            List<CatalogDtos.Card> products,
            ArticleDtos.Summary article,
                List<PaperDtos.Summary> papers) {
        public Section(
                String id,
                SectionType type,
                String title,
                Target moreTarget,
                List<CatalogDtos.Card> products,
                ArticleDtos.Summary article) {
            this(id, type, title, moreTarget, products, article, List.of());
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "HomeView")
    public record View(
            long revision,
            List<Banner> banners,
            List<Shortcut> shortcuts,
            List<Section> sections) {}

    public record Recommendation(@PositiveOrZero long revision,
            @NotBlank String contentId, boolean remove) {}
}
