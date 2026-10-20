package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;

class LearningResumeIntegrationTest extends EntitlementTestSupport {
    JsonNode chapter(JsonNode c, String title) throws Exception {
        return call("POST", "/admin/courses/" + c.path("id").asText() + "/chapters", a,
                Map.of("version", 0, "title", title, "sortNo", 0, "durationSeconds", 120,
                        "isTrial", false, "enabled", true), 200);
    }

    JsonNode item(String token) throws Exception {
        return call("GET", "/learning/courses", token, null, 200).path("items").get(0);
    }

    @Test
    void persistedResumeWorksAcrossSessionsAndSelectsLatestEnabledOwnedChapter() throws Exception {
        var c = course("DAYS", 30);
        var one = chapter(c, "第一节");
        var two = chapter(c, "第二节");
        deliver(paid(c.path("productId").asText(), "2026-10-10T02:00:00Z"));
        assertThat(item(b).path("resume").isNull()).isTrue();
        String prefix = "/learning/chapters/";
        call("PUT", prefix + one.path("id").asText() + "/progress", b, Map.of("positionSeconds", 37), 200);
        clock.now = clock.now.plusSeconds(10);
        call("PUT", prefix + two.path("id").asText() + "/progress", b, Map.of("positionSeconds", 52), 200);
        String anotherSession = buyer("buyer");
        var resume = item(anotherSession).path("resume");
        assertThat(resume.path("chapterId").asText()).isEqualTo(two.path("id").asText());
        assertThat(resume.path("chapterTitle").asText()).isEqualTo("第二节");
        assertThat(resume.path("positionSeconds").asInt()).isEqualTo(52);
        assertThat(resume.path("lastLearnedAt").asText()).isEqualTo(clock.now.toString());
        assertThat(call("GET", "/learning/courses", buyer("buyer-expired-member"), null, 200).path("items")).isEmpty();
        jdbc.update("update course_chapter set enabled=false where id=?", two.path("id").asText());
        assertThat(item(b).path("resume").path("chapterId").asText()).isEqualTo(one.path("id").asText());
        jdbc.update("update course_chapter set duration_seconds=20 where id=?", one.path("id").asText());
        assertThat(item(b).path("resume").path("positionSeconds").asInt()).isEqualTo(20);
        clock.now = java.time.Instant.parse("2026-11-10T02:00:00Z");
        b = buyer("buyer");
        assertThat(item(b).path("status").asText()).isEqualTo("EXPIRED");
        call("PUT", prefix + one.path("id").asText() + "/progress", b, Map.of("positionSeconds", 10), 403);
    }
}
