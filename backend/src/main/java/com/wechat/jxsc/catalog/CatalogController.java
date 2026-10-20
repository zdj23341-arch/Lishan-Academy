package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final ProductService products;
    private final CategoryService categories;
    private final MembershipPlanService plans;

    public CatalogController(
            ProductService products, CategoryService categories, MembershipPlanService plans) {
        this.products = products;
        this.categories = categories;
        this.plans = plans;
    }

    @GetMapping("/products")
    public ApiResponse<PageResult<CatalogDtos.Card>> products(
            @RequestParam(required = false) Product.Type type,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String categoryId,
            @RequestParam(defaultValue = "DEFAULT") CatalogDtos.Sort sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(
                products.list(type, keyword, categoryId, sort, new PageQuery(page, pageSize)));
    }

    @GetMapping("/products/{id}")
    public ApiResponse<CatalogDtos.Detail> product(@PathVariable long id) {
        return ApiResponse.ok(products.detail(id));
    }

    @GetMapping("/categories")
    public ApiResponse<PageResult<CatalogDtos.CategoryView>> categories(
            @RequestParam(required = false) Product.Type type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(categories.list(false, type, new PageQuery(page, pageSize)));
    }

    @GetMapping("/membership/plans")
    public ApiResponse<PageResult<MembershipPlanService.PlanView>> plans(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(plans.list(new PageQuery(page, pageSize)));
    }
}
