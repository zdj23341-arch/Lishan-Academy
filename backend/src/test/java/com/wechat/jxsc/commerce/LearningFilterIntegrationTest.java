package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Samples exist only in the runner-owned MySQL database; no real orders or rights are changed. */
class LearningFilterIntegrationTest extends EntitlementTestSupport {
    String grant(JsonNode course, Instant starts, Instant expires) {
        jdbc.update("insert ignore into entitlement_subject(user_id) values (?)", uid);
        String source = "DEV-learning-filter-" + UUID.randomUUID();
        jdbc.update("insert into entitlement(user_id,source_key,entitlement_type,target_id,paid_at,starts_at,expires_at)"
                + " values (?,?,'COURSE_VIEW',?,?,?,?)", uid, source, course.path("id").asLong(),
                Timestamp.from(starts), Timestamp.from(starts), expires == null ? null : Timestamp.from(expires));
        return source;
    }

    JsonNode chapter(JsonNode course, boolean enabled) throws Exception {
        return call("POST", "/admin/courses/" + course.path("id").asText() + "/chapters", a,
                Map.of("version", 0, "title", "筛选测试章节", "sortNo", 0, "durationSeconds", 120,
                        "isTrial", false, "enabled", enabled), 200);
    }

    JsonNode owned(boolean completed) throws Exception {
        var course = course("PERMANENT", null);
        grant(course, clock.now.minusSeconds(60), null);
        var chapter = chapter(course, true);
        if (completed) call("PUT", "/learning/chapters/" + chapter.path("id").asText() + "/progress", b,
                Map.of("positionSeconds", 120), 200);
        return course;
    }

    JsonNode list(String status, int page) throws Exception {
        return call("GET", "/learning/courses?page=" + page + "&pageSize=10"
                + (status == null ? "" : "&status=" + status), b, null, 200);
    }

    List<String> ids(JsonNode result) {
        List<String> ids = new ArrayList<>();
        result.path("items").forEach(item -> ids.add(item.path("courseId").asText()));
        return ids;
    }

    @Test
    void filtersBeforePaginationAndKeepsEntitlementStatusAndLegacyList() throws Exception {
        List<String> inProgress = new ArrayList<>();
        for (int i = 0; i < 12; i++) inProgress.add(owned(false).path("id").asText());
        var completedOne = owned(true);
        var completedTwo = owned(true);
        var expired = course("DAYS", 1);
        grant(expired, clock.now.minusSeconds(180), clock.now.minusSeconds(1));
        chapter(expired, true);
        var revoked = owned(false);
        String source = jdbc.queryForObject("select source_key from entitlement where user_id=? and target_id=?", String.class,
                uid, revoked.path("id").asLong());
        jdbc.update("insert into entitlement_event(user_id,source_key,event_key,event_type,detail_json) values (?,?,?,'REVOKE','{}')",
                uid, source, "DEV-revoke-" + UUID.randomUUID());
        assertThat(list("IN_PROGRESS", 1).path("total").asInt()).isEqualTo(12);
        assertThat(list("IN_PROGRESS", 2).path("items")).hasSize(2);
        var empty = course("PERMANENT", null);
        grant(empty, clock.now.minusSeconds(60), null);
        inProgress.add(empty.path("id").asText());
        chapter(completedOne, false); // Disabled chapters do not prevent completion.

        var first = list("IN_PROGRESS", 1);
        var second = list("IN_PROGRESS", 2);
        assertThat(first.path("total").asInt()).isEqualTo(13);
        assertThat(second.path("total").asInt()).isEqualTo(13);
        assertThat(first.path("items")).hasSize(10);
        assertThat(second.path("items")).hasSize(3);
        List<String> combined = new ArrayList<>(ids(first));
        combined.addAll(ids(second));
        assertThat(combined).containsExactlyElementsOf(inProgress.reversed());
        assertThat(list("IN_PROGRESS", 3).path("items")).isEmpty();
        var completed = list("COMPLETED", 1);
        assertThat(ids(completed)).containsExactly(completedTwo.path("id").asText(), completedOne.path("id").asText());
        assertThat(completed.path("total").asInt()).isEqualTo(2);
        assertThat(completed.path("items").get(0).path("status").asText()).isEqualTo("ACTIVE");
        var old = list("EXPIRED", 1);
        assertThat(ids(old)).containsExactly(expired.path("id").asText());
        assertThat(old.path("total").asInt()).isEqualTo(1);
        assertThat(old.path("items").get(0).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(list(null, 1).path("total").asInt()).isEqualTo(17);
        call("PUT", "/learning/chapters/" + chapter(revoked, true).path("id").asText() + "/progress", b,
                Map.of("positionSeconds", 30), 403);
    }

    @Test
    void expiryBoundaryRenewalFutureGrantsAndRevocationUseExistingRightsAuthority() throws Exception {
        var course = course("DAYS", 1);
        var chapter = chapter(course, true);
        Instant boundary = clock.now.plusSeconds(60);
        grant(course, clock.now.minusSeconds(60), boundary);
        assertThat(ids(list("IN_PROGRESS", 1))).containsExactly(course.path("id").asText());
        clock.now = boundary;
        b = buyer("buyer");
        assertThat(ids(list("EXPIRED", 1))).containsExactly(course.path("id").asText());
        String renewed = grant(course, boundary, boundary.plusSeconds(600));
        assertThat(ids(list("IN_PROGRESS", 1))).containsExactly(course.path("id").asText());
        assertThat(list("EXPIRED", 1).path("total").asInt()).isZero();
        grant(course, boundary.plusSeconds(300), boundary.plusSeconds(900)); // Future source cannot mask active rights.
        call("PUT", "/learning/chapters/" + chapter.path("id").asText() + "/progress", b,
                Map.of("positionSeconds", 120), 200);
        assertThat(ids(list("COMPLETED", 1))).containsExactly(course.path("id").asText());
        jdbc.update("insert into entitlement_event(user_id,source_key,event_key,event_type,detail_json) values (?,?,?,'REVOKE','{}')",
                uid, renewed, "DEV-revoke-" + UUID.randomUUID());
        assertThat(list("COMPLETED", 1).path("total").asInt()).isZero();
        assertThat(list("IN_PROGRESS", 1).path("total").asInt()).isZero();
        assertThat(list("EXPIRED", 1).path("total").asInt()).isZero(); // Remaining latest source is pending, not expired.
        assertThat(list(null, 1).path("items").get(0).path("status").asText()).isEqualTo("PENDING");
        call("PUT", "/learning/chapters/" + chapter.path("id").asText() + "/progress", b,
                Map.of("positionSeconds", 30), 403);
    }

    @Test
    void unknownFilterIsBadRequestAndListsAreAccountScoped() throws Exception {
        var result = call("GET", "/learning/courses?status=ACTIVE", b, null, 400);
        assertThat(result.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        owned(false);
        String other = buyer("buyer-expired-member");
        for (String status : List.of("IN_PROGRESS", "COMPLETED", "EXPIRED")) {
            var page = call("GET", "/learning/courses?status=" + status, other, null, 200);
            assertThat(page.path("items")).isEmpty();
            assertThat(page.path("total").asInt()).isZero();
        }
    }
}
