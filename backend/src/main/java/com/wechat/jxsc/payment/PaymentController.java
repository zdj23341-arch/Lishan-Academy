package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentWorkflow payments;

    public PaymentController(PaymentWorkflow payments) {
        this.payments = payments;
    }

    @PostMapping("/wechat/prepay")
    public ApiResponse<PaymentDtos.View> prepay(
            @Valid @RequestBody PaymentDtos.Prepay r,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(payments.prepay(CommerceSupport.id(r.orderId()), key));
    }
}
