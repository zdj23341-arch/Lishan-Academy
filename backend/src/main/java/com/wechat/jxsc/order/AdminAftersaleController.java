package com.wechat.jxsc.order;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.storage.AssetService;
import com.wechat.jxsc.fulfillment.*;

import jakarta.validation.Valid;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/aftersales")
@PreAuthorize("@actorAuthorization.hasPermission('FINANCE_OPERATE')")
public class AdminAftersaleController {
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final AftersaleService aftersales;
    private final AssetService assets;

    public AdminAftersaleController(
            com.fasterxml.jackson.databind.ObjectMapper json,
            AftersaleService aftersales,
            AssetService assets) {
        this.json = json;
        this.aftersales = aftersales;
        this.assets = assets;
    }

    @GetMapping
    public ApiResponse<PageResult<OrderDtos.AftersaleView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(aftersales.list(new PageQuery(page, pageSize)));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDtos.AftersaleView> get(@PathVariable long id) {
        return ApiResponse.ok(aftersales.get(id, true));
    }

    @PostMapping("/{id}/preview-decision")
    public ApiResponse<OrderDtos.DecisionPreview> preview(
            @PathVariable long id, @Valid @RequestBody OrderDtos.Decision r) {
        return ApiResponse.ok(aftersales.preview(id, r));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<OrderDtos.AftersaleView> approve(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderDtos.Decision r) {
        return ApiResponse.ok(
                json.convertValue(aftersales.approve(id, r, key), OrderDtos.AftersaleView.class));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<OrderDtos.AftersaleView> reject(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderDtos.Reason r) {
        return ApiResponse.ok(
                json.convertValue(aftersales.reject(id, r, key), OrderDtos.AftersaleView.class));
    }

    @PostMapping("/{id}/receive-return")
    @PreAuthorize("@actorAuthorization.hasPermission('ORDER_OPERATE')")
    public ApiResponse<OrderDtos.AftersaleView> receive(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody FulfillmentDtos.ReceiveReturn r) {
        return ApiResponse.ok(
                json.convertValue(aftersales.receive(id, r, key), OrderDtos.AftersaleView.class));
    }

    @GetMapping("/{id}/evidence/{assetId}/content")
    public ResponseEntity<InputStreamResource> evidence(
            @PathVariable long id, @PathVariable long assetId) {
        var bytes = assets.openProtected(aftersales.evidence(id, assetId, true));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(bytes.mime()))
                .contentLength(bytes.bytes())
                .body(new InputStreamResource(bytes.stream()));
    }
}
