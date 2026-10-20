package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CommercePrivacyIntegrationTest extends CommerceTestSupport {
    @Test
    void cachedOrderResponseNeverStoresPlaintextAddressAndRetryStillReturnsOriginal()
            throws Exception {
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 3, 2000);
        var q = quote(b, p, address(b).path("id").asText(), 1);
        String key = UUID.randomUUID().toString();
        var body = Map.of("quoteId", q.path("quoteId").asText());
        var o = write("POST", "/orders", b, body, key, 200);
        String stored =
                jdbc.queryForObject(
                        "select response_json from idempotency_record where operation_key=? and"
                                + " scope='CREATE_ORDER'",
                        String.class,
                        key);
        assertThat(stored).doesNotContain("DEV Buyer", "13800000000", "DEV test address 1");
        assertThat(write("POST", "/orders", b, body, key, 200)).isEqualTo(o);
        var lookup = call("GET", "/operations/" + key + "?scope=CREATE_ORDER", b, null, 200);
        assertThat(lookup.path("response")).isEqualTo(o);
    }
}
