package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;

@AutoConfigureMockMvc
class UiContractOpenApiTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void exportsBackendUiHandoffWithReadableFieldsAndServerFilters() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var doc = json.readTree(response.getContentAsByteArray());
        assertThat(doc.at("/paths/~1api~1v1~1admin~1content-candidates/get").isMissingNode()).isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1admin~1course-workflows/post").isMissingNode()).isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1admin~1course-workflows~1operations~1{operationKey}/get").isMissingNode()).isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1admin~1home~1sections~1{sectionId}~1recommend/post").isMissingNode()).isFalse();
        assertThat(doc.at("/components/schemas/HomeSectionInput/properties").has("selectionMode")).isTrue();
        assertThat(doc.at("/components/schemas/HomeSectionInput/properties").has("displayLimit")).isTrue();
        assertThat(doc.at("/components/schemas/HomeSectionInput/properties").has("pinnedIds")).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1admin~1users/get/security")).isNotEmpty();
        assertThat(doc.at("/paths/~1api~1v1~1admin~1users~1{id}~1status/patch").isMissingNode()).isFalse();
        for (String schema : List.of("ArticleWrite", "ArticleAdmin", "ArticleView"))
            for (String field : List.of("authorName", "categoryName"))
                assertThat(doc.path("components").path("schemas").path(schema).path("properties").has(field)).as(schema + "." + field).isTrue();
        assertThat(doc.at("/components/schemas/ArticleAdmin/properties").has("updatedAt")).isTrue();
        assertThat(doc.at("/components/schemas/LearningCourseView/properties").has("resume")).isTrue();
        assertThat(acceptsNull(doc, doc.at("/components/schemas/LearningCourseView/properties/resume"))).as("No progress is a valid null resume").isTrue();
        assertThat(doc.at("/components/schemas/AdminCustomerView/properties").has("membership")).isTrue();
        var membership = doc.at("/components/schemas/AdminCustomerView/properties/membership");
        if (membership.has("$ref")) membership = doc.at(membership.path("$ref").asText().substring(1));
        assertThat(acceptsNull(doc, membership.at("/properties/startsAt"))).as("NONE has no membership start").isTrue();
        assertThat(acceptsNull(doc, membership.at("/properties/expiresAt"))).as("NONE has no membership expiry").isTrue();
        var names = new ArrayList<String>();
        for (var p : doc.at("/paths/~1api~1v1~1admin~1membership~1records/get/parameters")) names.add(p.path("name").asText());
        assertThat(names).contains("userId", "status", "page", "pageSize");
        // Keep a standalone current snapshot; historical stage acceptance artifacts retain their scope.
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi-ui-backend.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n");
    }

    // OpenAPI 3.1 uses JSON Schema: $ref siblings apply together; adding type:null beside
    // an object-only reference does not make that reference accept null.
    private boolean acceptsNull(com.fasterxml.jackson.databind.JsonNode doc, com.fasterxml.jackson.databind.JsonNode schema) {
        if (schema.has("$ref") && !acceptsNull(doc, doc.at(schema.path("$ref").asText().substring(1)))) return false;
        var type = schema.path("type");
        if (type.isTextual() && !type.asText().equals("null")) return false;
        if (type.isArray() && java.util.stream.StreamSupport.stream(type.spliterator(), false).noneMatch(t -> t.asText().equals("null"))) return false;
        if (schema.has("anyOf") && java.util.stream.StreamSupport.stream(schema.path("anyOf").spliterator(), false).noneMatch(s -> acceptsNull(doc,s))) return false;
        if (schema.has("allOf") && java.util.stream.StreamSupport.stream(schema.path("allOf").spliterator(), false).anyMatch(s -> !acceptsNull(doc,s))) return false;
        return true;
    }
}
