package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("@actorAuthorization.hasPermission('ORDER_OPERATE')")
public class AdminInventoryQueryController {
    private final InventoryService inventory;
    public AdminInventoryQueryController(InventoryService inventory) { this.inventory = inventory; }

    @GetMapping("/inventory")
    public ApiResponse<PageResult<InventoryService.Item>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(inventory.list(new PageQuery(page, pageSize), keyword));
    }

    @GetMapping("/products/{id}/inventory")
    public ApiResponse<InventoryService.View> get(@PathVariable long id) {
        return ApiResponse.ok(inventory.get(id));
    }
}
