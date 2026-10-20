package com.wechat.jxsc.common.content;

import com.wechat.jxsc.common.ApiResponse;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/content-candidates")
@PreAuthorize("@actorAuthorization.hasPermission('CONTENT_EDIT')")
public class AdminCandidateController {
    private final AdminCandidateService candidates;

    public AdminCandidateController(AdminCandidateService candidates) {
        this.candidates = candidates;
    }

    @GetMapping
    public ApiResponse<AdminCandidateDtos.Page> list(
            @RequestParam String kind,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String ids,
            @RequestParam(defaultValue = "true") boolean usable,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<String> selected = ids == null || ids.isBlank()
                ? List.of()
                : Arrays.stream(ids.split(",", -1)).map(String::trim).toList();
        return ApiResponse.ok(candidates.find(kind, keyword, type, status, purpose, selected,
                usable, page, pageSize));
    }
}
