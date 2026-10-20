package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("@actorAuthorization.hasPermission('USER_MANAGE')")
public class AdminUsersController {
    private final AdminUsersService users;
    public AdminUsersController(AdminUsersService users) { this.users = users; }

    @GetMapping
    public ApiResponse<PageResult<AdminUserDtos.View>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AdminUserDtos.Status status) {
        return ApiResponse.ok(users.list(new PageQuery(page, pageSize), keyword, status));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminUserDtos.View> status(@PathVariable long id,
            @Valid @RequestBody AdminUserDtos.StatusWrite body) {
        return ApiResponse.ok(users.status(id, body));
    }
}
