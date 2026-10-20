package com.wechat.jxsc.common.storage;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/files")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminFileController {
    private final AssetService assets;

    public AdminFileController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping
    public ApiResponse<PageResult<AssetDtos.AdminView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(assets.adminList(new PageQuery(page, pageSize)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetDtos.AdminView> get(@PathVariable long id) {
        return ApiResponse.ok(assets.adminGet(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<AssetDtos.AdminView> disable(
            @PathVariable long id, @Valid @RequestBody AssetDtos.Disable request) {
        return ApiResponse.ok(assets.disable(id, request));
    }
}
