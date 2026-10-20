package com.wechat.jxsc.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wechat.jxsc.common.content.BodyBlocks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

public final class CatalogDtos {
    private CatalogDtos() {}

    public enum Sort {
        DEFAULT,
        PRICE_ASC,
        PRICE_DESC,
        NEWEST
    }

    public enum ProductStatus { DRAFT, ON_SALE, OFF_SALE }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogBookAdmin")
    public record BookAdmin(
            int quantityLimit, String shippingSummary,
            int availableStock, int lockedStock, int physicalStock,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Independent book_inventory version; send as inventory adjustment version, never product version")
            long inventoryVersion) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogBookInput")
    public record BookInput(
            @Min(1) @Max(99) int quantityLimit,
            @NotBlank @Size(max = 500) String shippingSummary) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogMembershipInput")
    public record MembershipInput(
            @io.swagger.v3.oas.annotations.media.Schema(
                            description =
                                    "Initial duration on creation. Product edits must submit the"
                                            + " current duration or receive 409 VERSION_CONFLICT;"
                                            + " change existing terms through PATCH"
                                            + " /admin/membership/plans/{id}.")
                    @Min(6)
                    @Max(12)
                    int durationMonths) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogProductWrite")
    public record ProductWrite(
            @NotNull @PositiveOrZero Long version,
            @NotNull Product.Type type,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) String subtitle,
            String coverAssetId,
            @NotNull @Min(0) @Max(100000000) Long priceFen,
            @Min(0) @Max(100000000) Long originalPriceFen,
            int sortNo,
            @NotNull @Size(max = 50) List<String> categoryIds,
            @NotNull @Size(max = 100) @Valid List<BodyBlocks.Input> contentBlocks,
            @Valid BookInput book,
            @Valid MembershipInput membership) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogCard")
    public record Card(
            String id,
            Product.Type type,
            String title,
            String coverUrl,
            long priceFen,
            Long originalPriceFen,
            String status,
            String subtitle,
            @io.swagger.v3.oas.annotations.media.Schema(
                            description = "Course content duration in minutes; null for non-course products")
                    Integer durationMinutes) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogBookDetail")
    public record BookDetail(int availableStock, int quantityLimit, String shippingSummary) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogCourseDetail")
    public record CourseDetail(
            String courseId,
            String validityType,
            Integer validityDays,
            Integer durationMinutes,
            String entitlement,
            int chaptersSummary) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogMembershipDetail")
    public record MembershipDetail(int durationMonths) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogMembershipAdmin")
    public record MembershipAdmin(String id, long version, int durationMonths, String status) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogDetail")
    public record Detail(
            String id,
            Product.Type type,
            String title,
            String coverUrl,
            long priceFen,
            Long originalPriceFen,
            String status,
            String subtitle,
            List<BodyBlocks.View> contentBlocks,
            long version,
            List<String> allowedActions,
            @JsonInclude(JsonInclude.Include.NON_NULL) BookDetail book,
            @JsonInclude(JsonInclude.Include.NON_NULL) CourseDetail course,
            @JsonInclude(JsonInclude.Include.NON_NULL) MembershipDetail membership) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogAdminProduct")
    public record AdminProduct(
            String id,
            long version,
            Product.Type type,
            String title,
            String subtitle,
            String coverAssetId,
            long priceFen,
            Long originalPriceFen,
            String status,
            int sortNo,
            List<String> categoryIds,
            List<BodyBlocks.Input> contentBlocks,
            BookAdmin book,
            MembershipAdmin membership) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogCategoryWrite")
    public record CategoryWrite(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 200) String name,
            @NotNull Product.Type type,
            String parentId,
            int sortNo,
            boolean enabled) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CatalogCategoryView")
    public record CategoryView(
            String id,
            long version,
            String name,
            Product.Type type,
            String parentId,
            int sortNo,
            boolean enabled) {}
}
