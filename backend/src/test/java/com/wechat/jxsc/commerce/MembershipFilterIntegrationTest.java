package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.util.*;

class MembershipFilterIntegrationTest extends EntitlementTestSupport {
    String path(String state) {
        return "/admin/membership/records?userId=" + uid + "&status=" + state + "&pageSize=1";
    }

    @Test
    void filtersApplyBeforePaginationAndUseActualIntervalsIncludingEndBoundary() throws Exception {
        String o = paid(member(6), "2026-10-10T02:00:00Z");
        deliver(o);
        var active = call("GET", path("ACTIVE"), a, null, 200);
        assertThat(active.path("total").asLong()).isEqualTo(1);
        assertThat(active.path("items").get(0).path("userId").asText()).isEqualTo(Long.toString(uid));
        assertThat(call("GET", path("EXPIRED"), a, null, 200).path("total").asLong()).isZero();
        assertThat(call("GET", path("ACTIVE") + "&page=2", a, null, 200).path("items")).isEmpty();
        assertThat(call("GET", path("ACTIVE") + "&page=2", a, null, 200).path("total").asLong()).isEqualTo(1);
        clock.now = java.time.Instant.parse("2027-04-10T02:00:00Z");
        a = admin("SUPER_ADMIN");
        assertThat(call("GET", path("ACTIVE"), a, null, 200).path("total").asLong()).isZero();
        assertThat(call("GET", path("EXPIRED"), a, null, 200).path("items").get(0).path("status").asText()).isEqualTo("EXPIRED");
        clock.now = java.time.Instant.parse("2026-10-09T02:00:00Z");
        assertThat(call("GET", path("PENDING"), a, null, 200).path("total").asLong()).isEqualTo(1);
        assertThat(call("GET", path("ACTIVE"), a, null, 200).path("total").asLong()).isZero();
        clock.now = java.time.Instant.parse("2026-10-10T02:00:00Z");
        refund(o, true, "membership-filter-" + UUID.randomUUID());
        assertThat(call("GET", path("NONE"), a, null, 200).path("total").asLong()).isEqualTo(1);
        assertThat(call("GET", path("ACTIVE"), a, null, 200).path("total").asLong()).isZero();
    }

    @Test
    void invalidFiltersAndUnprivilegedActorsCannotReadDirectory() throws Exception {
        call("GET", "/admin/membership/records?userId=0", a, null, 400);
        call("GET", "/admin/membership/records?userId=abc", a, null, 400);
        call("GET", "/admin/membership/records?status=PAID", a, null, 400);
        call("GET", "/admin/membership/records", b, null, 403);
        call("GET", "/admin/membership/records", admin("CONTENT_EDITOR"), null, 403);
    }
}
