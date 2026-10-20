package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.*;

/** Regression coverage for the 2026-10-11 education commerce handoff contract. */
class EducationCommerceContractIntegrationTest extends ContentHttpSupport {
    @Test
    void courseDurationIsPersistedSeparatelyFromViewingValidity() throws Exception {
        String token = admin("CONTENT_EDITOR");
        JsonNode product = createProduct(token, "COURSE", "契约课程", 3900);
        JsonNode course =
                call(
                        "POST",
                        "/admin/courses",
                        token,
                        Map.of(
                                "version",
                                0,
                                "productId",
                                product.path("id").asText(),
                                "validityType",
                                "DAYS",
                                "validityDays",
                                30,
                                "durationMinutes",
                                95),
                        200);

        assertThat(course.path("durationMinutes").asInt()).isEqualTo(95);
        product = publish(token, product);
        JsonNode detail = call("GET", "/products/" + product.path("id").asText(), null, null, 200);
        assertThat(detail.path("course").path("durationMinutes").asInt()).isEqualTo(95);
        assertThat(detail.path("course").path("validityDays").asInt()).isEqualTo(30);
    }

    @Test
    void courseDurationFallsBackToEnabledChapterDurationsWhenUnset() throws Exception {
        String token = admin("CONTENT_EDITOR");
        JsonNode product = createProduct(token, "COURSE", "按章节计算时长", 3900);
        JsonNode course =
                call(
                        "POST",
                        "/admin/courses",
                        token,
                        Map.of(
                                "version",
                                0,
                                "productId",
                                product.path("id").asText(),
                                "validityType",
                                "PERMANENT"),
                        200);
        String courseId = course.path("id").asText();
        call(
                "POST",
                "/admin/courses/" + courseId + "/chapters",
                token,
                Map.of(
                        "version",
                        0,
                        "title",
                        "第一节",
                        "sortNo",
                        0,
                        "durationSeconds",
                        61,
                        "isTrial",
                        false,
                        "enabled",
                        true),
                200);
        call(
                "POST",
                "/admin/courses/" + courseId + "/chapters",
                token,
                Map.of(
                        "version",
                        0,
                        "title",
                        "第二节",
                        "sortNo",
                        1,
                        "durationSeconds",
                        60,
                        "isTrial",
                        false,
                        "enabled",
                        true),
                200);

        JsonNode published = publish(token, product);
        JsonNode detail = call("GET", "/products/" + published.path("id").asText(), null, null, 200);
        assertThat(detail.path("course").path("durationMinutes").asInt()).isEqualTo(3);

        JsonNode page = call("GET", "/products?type=COURSE", null, null, 200);
        assertThat(page.path("items").findValue("durationMinutes").asInt()).isEqualTo(3);
    }

    @Test
    void papersExposeSchoolStageTypeAndProtectedResourceSummary() throws Exception {
        String token = admin("CONTENT_EDITOR");
        JsonNode attachment = upload(token, "PAPER_ATTACHMENT", "paper.png", "image/png", png());
        Map<String, Object> paperBody = new LinkedHashMap<>();
        paperBody.put("version", 0);
        paperBody.put("title", "六年级语文专项练习");
        paperBody.put("schoolStage", "PRIMARY");
        paperBody.put("stage", "PRIMARY");
        paperBody.put("grade", "6");
        paperBody.put("subject", "CHINESE");
        paperBody.put("paperType", "PRACTICE");
        paperBody.put("summary", "同步练习");
        paperBody.put("status", "PUBLISHED");
        paperBody.put("contentBlocks", List.of());
        paperBody.put(
                "attachments",
                List.of(
                        Map.of(
                                "assetId",
                                attachment.path("id").asText(),
                                "title",
                                "练习附件",
                                "sortNo",
                                0)));
        JsonNode paper =
                call(
                        "POST",
                        "/admin/papers",
                        token,
                        paperBody,
                        200);

        JsonNode page =
                call(
                        "GET",
                        "/papers?schoolStage=PRIMARY&grade=6&subject=CHINESE&paperType=PRACTICE",
                        null,
                        null,
                        200);
        assertThat(page.path("items").toString()).contains("schoolStage");
        assertThat(page.path("items").get(0).path("paperType").asText()).isEqualTo("PRACTICE");
        assertThat(page.path("items").get(0).path("accessCondition").asText())
                .isEqualTo("ACTIVE_MEMBER");
        assertThat(paper.path("schoolStage").asText()).isEqualTo("PRIMARY");
    }

    @Test
    void homeDraftSupportsCourseAndPaperColumnsAndNavigationTargets() throws Exception {
        String token = admin("CONTENT_EDITOR");
        JsonNode image = upload(token, "PUBLIC_IMAGE", "home.png", "image/png", png());
        JsonNode draft = call("GET", "/admin/home/draft", token, null, 200);
        Map<String, Object> target =
                Map.of("type", "PRODUCT_LIST", "filters", Map.of("type", "COURSE"));
        Map<String, Object> paperTarget =
                Map.of("type", "PAPER_LIST", "filters", Map.of("paperType", "PRACTICE"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", draft.path("revision").asLong());
        body.put("banners", List.of());
        body.put(
                "shortcuts",
                List.of(
                        Map.of(
                                "id",
                                "learning",
                                "label",
                                "我的学习",
                                "assetId",
                                image.path("id").asText(),
                                "sortNo",
                                0,
                                "enabled",
                                false,
                                "target",
                                Map.of("type", "LEARNING", "filters", Map.of()))));
        body.put(
                "sections",
                List.of(
                        Map.of(
                                "id",
                                "courses",
                                "type",
                                "COURSE_GRID",
                                "title",
                                "热门视频课程",
                                "sortNo",
                                1,
                                "enabled",
                                false,
                                "moreTarget",
                                target,
                                "productIds",
                                List.of()),
                        Map.of(
                                "id",
                                "papers",
                                "type",
                                "PAPER_GRID",
                                "title",
                                "会员试卷下载",
                                "sortNo",
                                2,
                                "enabled",
                                false,
                                "moreTarget",
                                paperTarget,
                                "productIds",
                                List.of(),
                                "paperIds",
                                List.of())));

        JsonNode saved = call("PUT", "/admin/home/draft", token, body, 200);
        assertThat(saved.path("sections").get(1).path("paperIds")).isNotNull();
        assertThat(saved.path("shortcuts").get(0).path("target").path("type").asText())
                .isEqualTo("LEARNING");
    }
}
