package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public catalog of logistics carriers so clients render a picker instead of asking users to
 * type carrier codes by hand. The same list is enforced server-side by
 * {@link CarrierCatalog#require(String)} when shipments are recorded.
 */
@RestController
public class LogisticsController {
    private final CarrierCatalog catalog;

    public LogisticsController(CarrierCatalog catalog) {
        this.catalog = catalog;
    }

    public record CarrierView(String code, String name) {}

    @GetMapping("/api/v1/logistics/carriers")
    public ApiResponse<List<CarrierView>> carriers() {
        return ApiResponse.ok(
                catalog.list().stream().map(c -> new CarrierView(c.code(), c.name())).toList());
    }
}
