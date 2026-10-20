package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditController {
    private final OperationAuditService audit;

    public AdminAuditController(OperationAuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("@actorAuthorization.hasPermission('AUDIT_READ')")
    public ApiResponse<PageResult<OperationAuditService.AuditView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(audit.list(new PageQuery(page, pageSize)));
    }
}
