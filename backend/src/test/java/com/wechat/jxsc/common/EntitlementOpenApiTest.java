package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.*;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class EntitlementOpenApiTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    JsonNode resolve(JsonNode doc, JsonNode schema) {
        return schema.has("$ref") ? doc.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    JsonNode data(JsonNode doc, String path, String method) {
        var schema =
                doc.path("paths")
                        .path("/api/v1" + path)
                        .path(method)
                        .at("/responses/200/content/application~1json/schema");
        if (schema.isMissingNode())
            schema =
                    doc.path("paths")
                            .path("/api/v1" + path)
                            .path(method)
                            .at("/responses/200/content/*~1*/schema");
        return resolve(doc, resolve(doc, schema).at("/properties/data"));
    }

    @Test
    void learningAndAdminCourseResponsesResolveToTheirOwnExactFields() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var doc = json.readTree(response.getContentAsByteArray());
        var learning =
                resolve(doc, data(doc, "/learning/courses", "get").at("/properties/items/items"));
        java.util.Set<String> fields = new java.util.HashSet<>();
        learning.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "courseId",
                        "productId",
                        "title",
                        "status",
                        "expiresAt",
                        "completedChapters",
                        "totalChapters",
                        "resume");
        var admin = data(doc, "/admin/courses/{id}", "get");
        fields.clear();
        admin.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "id",
                        "version",
                        "productId",
                        "validityType",
                        "validityDays",
                        "teacherName",
                        "durationMinutes");
        var adminList =
                resolve(doc, data(doc, "/admin/courses", "get").at("/properties/items/items"));
        assertThat(adminList).isEqualTo(admin);
        assertThat(learning).isNotEqualTo(admin);
    }

    @Test
    void membershipViewSchemaMatchesSharedStateAndPricedPlanContract() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var doc = json.readTree(response.getContentAsByteArray());
        var member = data(doc, "/membership/me", "get");
        var properties = member.path("properties");
        java.util.Set<String> fields = new java.util.HashSet<>();
        properties.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "level",
                        "state",
                        "status",
                        "startsAt",
                        "expiresAt",
                        "canDownload",
                        "plans");
        assertThat(properties.at("/state/enum"))
                .isEqualTo(json.readTree("[\"NONE\",\"ACTIVE\",\"EXPIRED\"]"));
        assertThat(properties.at("/canDownload/type").asText()).isEqualTo("boolean");
        assertThat(properties.at("/plans/type").asText()).isEqualTo("array");
        var plan = resolve(doc, properties.at("/plans/items")).path("properties");
        fields.clear();
        plan.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "productId", "durationMonths", "priceFen", "projectedExpiresAt");
        assertThat(plan.at("/productId/type").asText()).isEqualTo("string");
        assertThat(plan.at("/durationMonths/enum")).isEqualTo(json.readTree("[6,12]"));
        assertThat(plan.at("/durationMonths/type").asText()).isEqualTo("integer");
        assertThat(plan.at("/priceFen/type").asText()).isEqualTo("integer");
        assertThat(plan.at("/projectedExpiresAt/format").asText()).isEqualTo("date-time");
    }

    @Test
    void stageThreePublicSchemasHaveDistinctTypedFields() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var doc = json.readTree(response.getContentAsByteArray());
        var member = data(doc, "/membership/me", "get");
        assertThat(member.path("properties").has("status")).isTrue();
        assertThat(member.path("properties").has("expiresAt")).isTrue();
        var progress = data(doc, "/learning/chapters/{chapterId}/progress", "put");
        assertThat(progress.path("properties").has("positionSeconds")).isTrue();
        assertThat(progress.path("properties").has("completed")).isTrue();
        var file = data(doc, "/papers/{id}/files/{fileId}/authorize", "post");
        assertThat(file.path("properties").has("url")).isTrue();
        assertThat(resolve(doc, file.at("/properties/file")).path("properties").has("mime"))
                .isTrue();
        var records = data(doc, "/admin/membership/records", "get");
        assertThat(records.path("properties").has("total")).isTrue();
        assertThat(
                        resolve(doc, records.at("/properties/items/items"))
                                .path("properties")
                                .has("userId"))
                .isTrue();
    }
}
