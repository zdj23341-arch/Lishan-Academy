package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/categories")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminCategoryController {
    private final CategoryService categories;

    public AdminCategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    public ApiResponse<PageResult<CatalogDtos.CategoryView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Product.Type type,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(categories.list(true, type, new PageQuery(page, pageSize), keyword, enabled));
    }

    @GetMapping("/{id}")
    public ApiResponse<CatalogDtos.CategoryView> get(@PathVariable long id) {
        return ApiResponse.ok(categories.get(id));
    }

    @PostMapping
    public ApiResponse<CatalogDtos.CategoryView> create(
            @Valid @RequestBody CatalogDtos.CategoryWrite r) {
        return ApiResponse.ok(categories.save(null, r));
    }

    @PatchMapping("/{id}")
    public ApiResponse<CatalogDtos.CategoryView> edit(
            @PathVariable long id, @Valid @RequestBody CatalogDtos.CategoryWrite r) {
        return ApiResponse.ok(categories.save(id, r));
    }
}
