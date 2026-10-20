package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.order.*;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dev")
@Conditional(DevPaymentCondition.class)
public class DevPaymentController {
    private final PaymentWorkflow payments;
    private final AftersaleService aftersales;

    public DevPaymentController(PaymentWorkflow payments, AftersaleService aftersales) {
        this.payments = payments;
        this.aftersales = aftersales;
    }

    @PostMapping("/orders/{id}/pay")
    public ApiResponse<OrderDtos.View> pay(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PaymentDtos.Scenario r) {
        return ApiResponse.ok(payments.devPay(id, key, r.scenario()));
    }

    @PostMapping("/aftersales/{id}/refund")
    public ApiResponse<OrderDtos.AftersaleView> refund(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PaymentDtos.Scenario r) {
        payments.devRefund(aftersales.refundId(id), key, r.scenario());
        return ApiResponse.ok(aftersales.get(id, false));
    }
}
