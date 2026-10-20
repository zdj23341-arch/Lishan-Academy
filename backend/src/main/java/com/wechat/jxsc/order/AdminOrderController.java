package com.wechat.jxsc.order;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.fulfillment.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("@actorAuthorization.hasPermission('ORDER_OPERATE')")
public class AdminOrderController {
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final AdminOrderQuery query;
    private final OrderService orders;
    private final ShipmentService shipments;
    private final ShipmentWorkflow logistics;
    private final AftersaleService aftersales;

    public AdminOrderController(
            com.fasterxml.jackson.databind.ObjectMapper json,
            AdminOrderQuery query,
            OrderService orders,
            ShipmentService shipments,
            ShipmentWorkflow logistics,
            AftersaleService aftersales) {
        this.json = json;
        this.query = query;
        this.orders = orders;
        this.shipments = shipments;
        this.logistics = logistics;
        this.aftersales = aftersales;
    }

    @GetMapping
    public ApiResponse<PageResult<OrderDtos.View>> list(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String orderStatus,
            @RequestParam(required = false) String refundStatus,
            @RequestParam(required = false) String fulfillmentStatus,
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(
                query.list(
                        new PageQuery(page, pageSize),
                        orderNo,
                        orderStatus,
                        refundStatus,
                        fulfillmentStatus,
                        from,
                        to));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDtos.View> get(@PathVariable long id) {
        return ApiResponse.ok(orders.get(id, true));
    }

    @PostMapping("/{id}/ship")
    public ApiResponse<OrderDtos.View> ship(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody FulfillmentDtos.Ship r) {
        var result = shipments.ship(id, r, key);
        try {
            logistics.sync(id);
        } catch (com.wechat.jxsc.common.ApiException ignored) {
        }
        return ApiResponse.ok(json.convertValue(result, OrderDtos.View.class));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("@actorAuthorization.hasPermission('FINANCE_OPERATE')")
    public ApiResponse<OrderDtos.AftersaleView> exceptionRefund(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderDtos.ApplyRefund r) {
        return ApiResponse.ok(
                json.convertValue(
                        aftersales.apply(id, r, key, true), OrderDtos.AftersaleView.class));
    }
}
