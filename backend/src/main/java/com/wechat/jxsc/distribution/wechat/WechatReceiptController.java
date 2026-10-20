package com.wechat.jxsc.distribution.wechat;

import com.wechat.jxsc.common.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/distribution/receipt-profile")
public class WechatReceiptController {
    private final WechatReceiptProfile profile;

    public WechatReceiptController(WechatReceiptProfile profile) {
        this.profile = profile;
    }

    public record Bind(@Size(max = 64) String realName) {
        @Override
        public String toString() {
            return "ReceiptProfile[redacted]";
        }
    }

    @PostMapping
    public ApiResponse<WechatReceiptProfile.View> bind(@Valid @RequestBody Bind request) {
        return ApiResponse.ok(profile.bind(request.realName()));
    }
}
