package com.wechat.jxsc.order;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.fulfillment.*;
import com.wechat.jxsc.payment.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OrderController {
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final OrderService orders;
    private final PaymentWorkflow payments;
    private final ShipmentService shipments;
    private final AftersaleService aftersales;

    public OrderController(
            com.fasterxml.jackson.databind.ObjectMapper json,
            OrderService orders,
            PaymentWorkflow payments,
            ShipmentService shipments,
            AftersaleService aftersales) {
        this.json = json;
        this.orders = orders;
        this.payments = payments;
        this.shipments = shipments;
        this.aftersales = aftersales;
    }

    @PostMapping("/orders/preview")
    public ApiResponse<OrderDtos.Quote> preview(@Valid @RequestBody OrderDtos.Preview r) {
        return ApiResponse.ok(orders.preview(r));
    }

    @PostMapping("/orders")
    public ApiResponse<OrderDtos.View> create(
            @Valid @RequestBody OrderDtos.Create r, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(json.convertValue(orders.create(r, key), OrderDtos.View.class));
    }

    @GetMapping("/orders")
    public ApiResponse<PageResult<OrderDtos.View>> list(
            @RequestParam(defaultValue = "ALL") String tab,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(orders.list(tab, new PageQuery(page, pageSize), false));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderDtos.View> get(@PathVariable long id) {
        return ApiResponse.ok(orders.get(id, false));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<OrderDtos.View> cancel(
            @PathVariable long id, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(payments.cancel(id, key));
    }

    @PostMapping("/orders/{id}/confirm-receipt")
    public ApiResponse<OrderDtos.View> receive(
            @PathVariable long id, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(
                json.convertValue(shipments.receive(id, key, false), OrderDtos.View.class));
    }

    @PostMapping("/orders/{id}/refund")
    public ApiResponse<OrderDtos.AftersaleView> refund(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderDtos.ApplyRefund r) {
        return ApiResponse.ok(
                json.convertValue(
                        aftersales.apply(id, r, key, false), OrderDtos.AftersaleView.class));
    }

    @GetMapping("/orders/{id}/logistics")
    public ApiResponse<FulfillmentDtos.LogisticsView> logistics(@PathVariable long id) {
        return ApiResponse.ok(shipments.get(id, false));
    }

    @GetMapping("/shipments/{orderId}")
    public ApiResponse<FulfillmentDtos.LogisticsView> shipment(@PathVariable long orderId) {
        return ApiResponse.ok(shipments.get(orderId, false));
    }
}
