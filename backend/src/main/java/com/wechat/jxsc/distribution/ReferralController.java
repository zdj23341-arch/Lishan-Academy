package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {
    private final ReferralService service;

    public ReferralController(ReferralService service) {
        this.service = service;
    }

    @GetMapping(value = "/{code}/image", produces = "image/png")
    public org.springframework.http.ResponseEntity<byte[]> image(@PathVariable String code) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .body(service.image(code));
    }

    @PostMapping("/resolve")
    public ApiResponse<FinanceDtos.TouchView> resolve(
            @Valid @RequestBody FinanceDtos.ResolveRequest r) {
        return ApiResponse.ok(service.resolve(r));
    }

    @PostMapping("/claim")
    public ApiResponse<FinanceDtos.TouchView> claim(
            @Valid @RequestBody FinanceDtos.ClaimRequest r) {
        return ApiResponse.ok(service.claim(r));
    }
}
