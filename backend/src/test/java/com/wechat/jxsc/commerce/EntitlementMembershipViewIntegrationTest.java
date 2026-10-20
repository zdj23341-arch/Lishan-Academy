package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class EntitlementMembershipViewIntegrationTest extends EntitlementTestSupport {
    void planStatus(String product, int months, String status) throws Exception {
        var plan = call("GET", "/admin/products/" + product, a, null, 200).path("membership");
        call(
                "PATCH",
                "/admin/membership/plans/" + plan.path("id").asText(),
                a,
                Map.of(
                        "version",
                        plan.path("version").asLong(),
                        "durationMonths",
                        months,
                        "status",
                        status),
                200);
    }

    void assertState(JsonNode view, String state, String status, boolean canDownload) {
        Set<String> fields = new HashSet<>();
        view.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "level",
                        "state",
                        "status",
                        "startsAt",
                        "expiresAt",
                        "canDownload",
                        "plans");
        assertThat(view.path("level").asText()).isEqualTo("STANDARD");
        assertThat(view.path("state").asText()).isEqualTo(state);
        assertThat(view.path("status").asText()).isEqualTo(status);
        assertThat(view.path("canDownload").isBoolean()).isTrue();
        assertThat(view.path("canDownload").asBoolean()).isEqualTo(canDownload);
    }

    void assertPlan(JsonNode view, int index, String product, int months, long price, String end) {
        var plan = view.path("plans").path(index);
        Set<String> fields = new HashSet<>();
        plan.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "productId", "durationMonths", "priceFen", "projectedExpiresAt");
        assertThat(plan.path("productId").asText()).isEqualTo(product);
        assertThat(plan.path("durationMonths").asInt()).isEqualTo(months);
        assertThat(plan.path("priceFen").asLong()).isEqualTo(price);
        assertThat(plan.path("projectedExpiresAt").asText()).isEqualTo(end);
    }

    @Test
    void noHistoryOffersSixAndTwelveShanghaiCalendarMonthsWithoutCreatingRights() throws Exception {
        clock.now = Instant.parse("2026-08-30T18:00:00Z"); // August 31 in Shanghai
        String half = member(6), year = member(12);
        var view = me();
        assertState(view, "NONE", "NONE", false);
        assertThat(view.path("startsAt").isNull()).isTrue();
        assertThat(view.path("expiresAt").isNull()).isTrue();
        assertThat(view.path("plans").size()).isEqualTo(2);
        assertPlan(view, 0, half, 6, 100, "2027-02-27T18:00:00Z");
        assertPlan(view, 1, year, 12, 100, "2027-08-30T18:00:00Z");
        assertThat(me()).isEqualTo(view);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from membership_grant where user_id=?",
                                Integer.class,
                                uid))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from membership_record where user_id=?",
                                Integer.class,
                                uid))
                .isZero();
    }

    @Test
    void activeRenewalExtendsEffectiveExpiryAndFullRefundRecomputesOffers() throws Exception {
        String half = member(6), year = member(12);
        String first = paid(half, "2026-08-30T18:00:00Z");
        deliver(first);
        var view = me();
        assertState(view, "ACTIVE", "ACTIVE", true);
        assertThat(view.path("expiresAt").asText()).isEqualTo("2027-02-27T18:00:00Z");
        assertPlan(view, 0, half, 6, 100, "2027-08-27T18:00:00Z");
        assertPlan(view, 1, year, 12, 100, "2028-02-27T18:00:00Z");
        String second = paid(year, "2026-10-10T02:00:00Z");
        deliver(second);
        refund(first, true, "member-view-" + first);
        var after = me();
        assertState(after, "ACTIVE", "ACTIVE", true);
        assertThat(after.path("expiresAt").asText()).isEqualTo("2027-10-10T02:00:00Z");
        assertPlan(after, 0, half, 6, 100, "2028-04-10T02:00:00Z");
        assertPlan(after, 1, year, 12, 100, "2028-10-10T02:00:00Z");
    }

    @Test
    void expiredMembershipRenewsFromNowRatherThanExpiredEnd() throws Exception {
        String half = member(6), year = member(12);
        deliver(paid(half, "2025-08-30T18:00:00Z"));
        var view = me();
        assertState(view, "EXPIRED", "EXPIRED", false);
        assertThat(view.path("expiresAt").asText()).isEqualTo("2026-02-27T18:00:00Z");
        assertPlan(view, 0, half, 6, 100, "2027-04-10T02:00:00Z");
        assertPlan(view, 1, year, 12, 100, "2027-10-10T02:00:00Z");
    }

    @Test
    void futureOnlyMembershipIsNoneAndDoesNotPermitDownloads() throws Exception {
        String half = member(6), year = member(12);
        deliver(paid(year, "2027-01-01T02:00:00Z"));
        var view = me();
        assertState(view, "NONE", "PENDING", false);
        assertThat(view.path("startsAt").asText()).isEqualTo("2027-01-01T02:00:00Z");
        assertPlan(view, 0, half, 6, 100, "2028-04-10T02:00:00Z");
        assertPlan(view, 1, year, 12, 100, "2028-10-10T02:00:00Z");
    }

    @Test
    void gapAfterElapsedMembershipIsExpiredDespiteFutureRenewal() throws Exception {
        String half = member(6), year = member(12);
        deliver(paid(half, "2025-01-01T02:00:00Z"));
        deliver(paid(year, "2027-01-01T02:00:00Z"));
        var view = me();
        assertState(view, "EXPIRED", "PENDING", false);
        assertThat(view.path("startsAt").asText()).isEqualTo("2027-01-01T02:00:00Z");
        assertPlan(view, 0, half, 6, 100, "2028-04-10T02:00:00Z");
        assertPlan(view, 1, year, 12, 100, "2028-10-10T02:00:00Z");
    }

    @Test
    void offersUseCurrentServerPricesAndExcludeDraftOffSaleAndDisabledPlans() throws Exception {
        String disabled = member(6), offSale = member(12);
        var offSaleProduct = call("GET", "/admin/products/" + offSale, a, null, 200);
        call(
                "POST",
                "/admin/products/" + offSale + "/unpublish",
                a,
                Map.of(
                        "version",
                        offSaleProduct.path("version").asLong(),
                        "reason",
                        "DEV remove offer"),
                200);
        var draft = createProduct(a, "MEMBERSHIP", "DEV draft membership", 777);
        String half = member(6),
                year = member(12); // prior six-month product remains ON_SALE but disabled
        var edit = product("MEMBERSHIP", "DEV current price", 6250);
        edit.put("membership", Map.of("durationMonths", 6));
        edit.put(
                "version",
                call("GET", "/admin/products/" + half, a, null, 200).path("version").asLong());
        call("PATCH", "/admin/products/" + half, a, edit, 200);
        var view = me();
        assertThat(view.path("plans").size()).isEqualTo(2);
        assertPlan(view, 0, half, 6, 6250, "2027-04-10T02:00:00Z");
        assertPlan(view, 1, year, 12, 100, "2027-10-10T02:00:00Z");
        assertThat(view.path("plans").toString())
                .doesNotContain(
                        "\"productId\":\"" + disabled + "\"",
                        "\"productId\":\"" + offSale + "\"",
                        "\"productId\":\"" + draft.path("id").asText() + "\"");
        var current = call("GET", "/admin/products/" + year, a, null, 200);
        call(
                "POST",
                "/admin/products/" + year + "/unpublish",
                a,
                Map.of("version", current.path("version").asLong(), "reason", "DEV remove offer"),
                200);
        assertThat(me().path("plans").size()).isEqualTo(1);
        planStatus(year, 12, "ENABLED");
        assertThat(me().path("plans").size())
                .as("Enabled but off-sale is not an offer")
                .isEqualTo(1);
        planStatus(year, 12, "DISABLED");
        planStatus(draft.path("id").asText(), 12, "ENABLED");
        assertThat(me().path("plans").size()).as("Enabled but draft is not an offer").isEqualTo(1);
    }
}
