package com.wechat.jxsc.payment.wechat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/wechat")
public class WechatWebhookController {
    private final WechatCallbacks callbacks;

    public WechatWebhookController(WechatCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/payments")
    public ResponseEntity<Void> payment(
            @RequestBody byte[] body, @RequestHeader Map<String, String> headers) {
        callbacks.receive("payments", body, headers);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refunds")
    public ResponseEntity<Void> refund(
            @RequestBody byte[] body, @RequestHeader Map<String, String> headers) {
        callbacks.receive("refunds", body, headers);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transfers")
    public ResponseEntity<Void> transfer(
            @RequestBody byte[] body, @RequestHeader Map<String, String> headers) {
        callbacks.receive("transfers", body, headers);
        return ResponseEntity.noContent().build();
    }
}
