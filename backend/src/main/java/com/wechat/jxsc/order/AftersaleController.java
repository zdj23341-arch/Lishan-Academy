package com.wechat.jxsc.order;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.storage.AssetService;
import com.wechat.jxsc.fulfillment.*;

import jakarta.validation.Valid;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/aftersales")
public class AftersaleController {
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final AftersaleService aftersales;
    private final AssetService assets;

    public AftersaleController(
            com.fasterxml.jackson.databind.ObjectMapper json,
            AftersaleService aftersales,
            AssetService assets) {
        this.json = json;
        this.aftersales = aftersales;
        this.assets = assets;
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDtos.AftersaleView> get(@PathVariable long id) {
        return ApiResponse.ok(aftersales.get(id, false));
    }

    @PostMapping("/{id}/return-shipment")
    public ApiResponse<OrderDtos.AftersaleView> shipment(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody FulfillmentDtos.ReturnShipment r) {
        return ApiResponse.ok(
                json.convertValue(
                        aftersales.returnShipment(id, r, key), OrderDtos.AftersaleView.class));
    }

    @GetMapping("/{id}/evidence/{assetId}/content")
    public ResponseEntity<InputStreamResource> evidence(
            @PathVariable long id, @PathVariable long assetId) {
        var bytes = assets.openProtected(aftersales.evidence(id, assetId, false));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(bytes.mime()))
                .contentLength(bytes.bytes())
                .body(new InputStreamResource(bytes.stream()));
    }
}
