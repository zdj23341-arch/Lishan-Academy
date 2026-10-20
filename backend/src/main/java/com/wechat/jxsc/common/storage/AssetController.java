package com.wechat.jxsc.common.storage;

import com.wechat.jxsc.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class AssetController {
    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @PostMapping("/admin/files/presign")
    @PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
    public ApiResponse<AssetDtos.Ticket> adminPresign(@Valid @RequestBody AssetDtos.Presign r) {
        return ApiResponse.ok(assets.presign(r, true));
    }

    @PostMapping("/files/presign")
    public ApiResponse<AssetDtos.Ticket> presign(@Valid @RequestBody AssetDtos.Presign r) {
        return ApiResponse.ok(assets.presign(r, false));
    }

    @PostMapping("/admin/files/{id}/complete")
    @PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
    public ApiResponse<AssetDtos.View> adminComplete(
            @PathVariable long id, @RequestBody(required = false) AssetDtos.Completion request) {
        return ApiResponse.ok(assets.complete(id));
    }

    @PostMapping("/files/{id}/complete")
    public ApiResponse<AssetDtos.View> complete(
            @PathVariable long id, @RequestBody(required = false) AssetDtos.Completion request) {
        return ApiResponse.ok(assets.complete(id));
    }

    @PutMapping(value = "/local-uploads/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Void> upload(
            @PathVariable long id,
            @RequestHeader("X-Upload-Token") String token,
            HttpServletRequest request)
            throws IOException {
        assets.upload(id, token, request.getInputStream());
        return ApiResponse.ok(null);
    }

    @GetMapping("/assets/{id}/content")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> publicContent(
            @PathVariable long id) {
        var b = assets.publicBytes(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate().mustRevalidate())
                .contentType(MediaType.parseMediaType(b.mime()))
                .contentLength(b.bytes())
                .header("X-Content-Type-Options", "nosniff")
                .body(new org.springframework.core.io.InputStreamResource(b.stream()));
    }
}
