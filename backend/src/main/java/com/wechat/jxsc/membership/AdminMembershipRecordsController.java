package com.wechat.jxsc.membership;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/membership/records")
@PreAuthorize("@actorAuthorization.hasPermission('ORDER_OPERATE')")
public class AdminMembershipRecordsController {
    private final MembershipRecords records;

    public AdminMembershipRecordsController(MembershipRecords records) {
        this.records = records;
    }

    @GetMapping
    public ApiResponse<PageResult<MembershipDtos.Record>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) MembershipQuery.Status status) {
        return ApiResponse.ok(records.list(new PageQuery(page, pageSize),
                userId == null ? null : ContentSupport.id(userId), status));
    }
}
