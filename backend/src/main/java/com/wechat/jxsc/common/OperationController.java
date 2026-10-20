package com.wechat.jxsc.common;

import com.wechat.jxsc.auth.CurrentActor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationController {
    private final IdempotencyService operations;
    private final com.wechat.jxsc.common.commerce.ProtectedOperationResults protectedResults;

    public OperationController(
            IdempotencyService operations,
            com.wechat.jxsc.common.commerce.ProtectedOperationResults protectedResults) {
        this.operations = operations;
        this.protectedResults = protectedResults;
    }

    @GetMapping("/{key}")
    public ApiResponse<IdempotencyService.Outcome> lookup(
            @PathVariable String key, @RequestParam String scope) {
        var result = operations.lookup(CurrentActor.require(), scope, key);
        return ApiResponse.ok(
                new IdempotencyService.Outcome(
                        result.state(),
                        result.resourceId(),
                        protectedResults.reveal(result.response())));
    }
}
