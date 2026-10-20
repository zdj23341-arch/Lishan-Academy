package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/addresses")
public class AddressController {
    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    public ApiResponse<PageResult<FulfillmentDtos.AddressView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(addresses.list(new PageQuery(page, pageSize)));
    }

    @PostMapping
    public ApiResponse<FulfillmentDtos.AddressView> create(
            @Valid @RequestBody FulfillmentDtos.AddressWrite r) {
        return ApiResponse.ok(addresses.save(null, r));
    }

    @PatchMapping("/{id}")
    public ApiResponse<FulfillmentDtos.AddressView> edit(
            @PathVariable long id, @Valid @RequestBody FulfillmentDtos.AddressWrite r) {
        return ApiResponse.ok(addresses.save(id, r));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        addresses.delete(id);
        return ApiResponse.ok(null);
    }
}
