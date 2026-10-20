package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminProductController {
    private final ProductService products;

    public AdminProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    public ApiResponse<PageResult<CatalogDtos.AdminProduct>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Product.Type type,
            @RequestParam(required = false) CatalogDtos.ProductStatus status) {
        return ApiResponse.ok(products.adminList(new PageQuery(page, pageSize), keyword, type, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<CatalogDtos.AdminProduct> get(@PathVariable long id) {
        return ApiResponse.ok(products.getAdmin(id));
    }

    @PostMapping
    public ApiResponse<CatalogDtos.AdminProduct> create(
            @Valid @RequestBody CatalogDtos.ProductWrite r) {
        return ApiResponse.ok(products.save(null, r));
    }

    @PatchMapping("/{id}")
    public ApiResponse<CatalogDtos.AdminProduct> edit(
            @PathVariable long id, @Valid @RequestBody CatalogDtos.ProductWrite r) {
        return ApiResponse.ok(products.save(id, r));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<CatalogDtos.AdminProduct> publish(
            @PathVariable long id, @Valid @RequestBody ContentSupport.Lifecycle r) {
        return ApiResponse.ok(products.lifecycle(id, r, true));
    }

    @PostMapping("/{id}/unpublish")
    @io.swagger.v3.oas.annotations.Operation(
            description =
                    "Unpublish a previously published product while preserving its detail and"
                        + " history. DRAFT products return 409 INVALID_STATE and remain hidden from"
                        + " public detail.")
    public ApiResponse<CatalogDtos.AdminProduct> unpublish(
            @PathVariable long id, @Valid @RequestBody ContentSupport.Lifecycle r) {
        return ApiResponse.ok(products.lifecycle(id, r, false));
    }
}
