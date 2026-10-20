package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;

/** Completion used to require an exact position/duration match, so a player that stopped one
 * second short left a fully watched chapter stuck on in-progress forever. */
class LearningCompletionIntegrationTest extends EntitlementTestSupport {
    JsonNode chapter(JsonNode c, String title, int durationSeconds) throws Exception {
        return call("POST", "/admin/courses/" + c.path("id").asText() + "/chapters", a,
                Map.of("version", 0, "title", title, "sortNo", 0,
                        "durationSeconds", durationSeconds, "isTrial", false, "enabled", true),
                200);
    }

    JsonNode learnedCourse(String token, JsonNode c) throws Exception {
        for (JsonNode n : call("GET", "/learning/courses", token, null, 200).path("items"))
            if (n.path("courseId").asText().equals(c.path("id").asText())) return n;
        throw new AssertionError("course missing from /learning/courses");
    }

    JsonNode progress(String token, JsonNode ch, int positionSeconds) throws Exception {
        return call("PUT", "/learning/chapters/" + ch.path("id").asText() + "/progress", token,
                Map.of("positionSeconds", positionSeconds), 200);
    }

    @Test
    void completesAtDurationAndClampsOvershootInsteadOfRejecting() throws Exception {
        var c = course("DAYS", 30);
        var ch = chapter(c, "第一节", 120);
        deliver(paid(c.path("productId").asText(), "2026-10-10T02:00:00Z"));

        var shortOfEnd = progress(b, ch, 119);
        assertThat(shortOfEnd.path("completed").asBoolean()).isFalse();
        assertThat(shortOfEnd.path("positionSeconds").asInt()).isEqualTo(119);
        assertThat(learnedCourse(b, c).path("completedChapters").asInt()).isZero();

        var atEnd = progress(b, ch, 120);
        assertThat(atEnd.path("completed").asBoolean()).isTrue();
        assertThat(atEnd.path("positionSeconds").asInt()).isEqualTo(120);
        assertThat(learnedCourse(b, c).path("completedChapters").asInt()).isEqualTo(1);

        // Players may report past the declared duration (drifted metadata, stale local
        // duration). That used to be a 400 and lost the progress write entirely.
        var overshoot = progress(b, ch, 300);
        assertThat(overshoot.path("completed").asBoolean()).isTrue();
        assertThat(overshoot.path("positionSeconds").asInt()).isEqualTo(120);
        assertThat(learnedCourse(b, c).path("completedChapters").asInt()).isEqualTo(1);
    }

    @Test
    void shorteningAChapterKeepsFinishedUsersFinished() throws Exception {
        var c = course("DAYS", 30);
        var ch = chapter(c, "第一节", 600);
        deliver(paid(c.path("productId").asText(), "2026-10-10T02:00:00Z"));
        assertThat(progress(b, ch, 600).path("completed").asBoolean()).isTrue();

        jdbc.update("update course_chapter set duration_seconds=300 where id=?",
                Long.parseLong(ch.path("id").asText()));
        assertThat(learnedCourse(b, c).path("completedChapters").asInt()).isEqualTo(1);

        // A later report from the old duration has to land inside the new window.
        var clamped = progress(b, ch, 480);
        assertThat(clamped.path("positionSeconds").asInt()).isEqualTo(300);
        assertThat(clamped.path("completed").asBoolean()).isTrue();
    }
}
