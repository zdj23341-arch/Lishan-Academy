package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The signed-in customer's own download history; never another subject's rows. */
@RestController
@RequestMapping("/api/v1/downloads")
public class PaperDownloadController {
    private final PaperDownloadService downloads;

    public PaperDownloadController(PaperDownloadService downloads) {
        this.downloads = downloads;
    }

    @GetMapping
    public ApiResponse<List<PaperDtos.Download>> recent(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(downloads.recent(limit));
    }
}
