package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("@actorAuthorization.hasPermission('ORDER_OPERATE')")
public class AdminInventoryController {
    private final InventoryService inventory;

    public AdminInventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping("/{id}/inventory-adjustments")
    @io.swagger.v3.oas.annotations.Operation(description = "BOOK only. Atomic delta adjustment with reason and UUID Idempotency-Key; optional expected inventory version rejects stale writes with 409 VERSION_CONFLICT. The version belongs to book_inventory, not product. Replay of the same key and body returns the original result without checking the now-advanced version. Cannot consume reserved stock.")
    public ApiResponse<InventoryService.View> adjust(
            @PathVariable long id,
            @Valid @RequestBody InventoryService.Adjustment r,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(inventory.adjust(id, r, key));
    }
}
