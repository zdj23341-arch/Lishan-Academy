package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class EntitlementSchemaPayloadIntegrationTest extends EntitlementTestSupport {
    JsonNode document() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsByteArray());
    }

    JsonNode resolve(JsonNode doc, JsonNode schema) {
        while (schema.has("$ref")) schema = doc.at(schema.path("$ref").asText().substring(1));
        assertThat(schema.isMissingNode()).isFalse();
        return schema;
    }

    JsonNode data(JsonNode doc, String path) {
        var content = doc.path("paths").path("/api/v1" + path).at("/get/responses/200/content");
        assertThat(content.isObject()).isTrue();
        return resolve(
                doc, resolve(doc, content.elements().next().path("schema")).at("/properties/data"));
    }

    // Check the emitted JSON Schema type/enum/date-time contract against actual HTTP values.
    // In particular a missing required declaration must never make a present null acceptable.
    void accepts(JsonNode doc, JsonNode schema, JsonNode value, String path) {
        schema = resolve(doc, schema);
        // A nullable object is emitted as anyOf [<ref>, {"type":"null"}]; accept the value when
        // any branch accepts it, and still reject values that no branch describes.
        if (schema.has("anyOf")) {
            AssertionError failure = null;
            for (var branch : schema.path("anyOf")) {
                try {
                    accepts(doc, branch, value, path);
                    return;
                } catch (AssertionError e) {
                    failure = e;
                }
            }
            throw failure == null ? new AssertionError(path + " has no anyOf branch") : failure;
        }
        Set<String> types = new HashSet<>();
        var type = schema.path("type");
        if (type.isArray()) type.forEach(t -> types.add(t.asText()));
        else if (type.isTextual()) types.add(type.asText());
        String actual =
                value.isNull()
                        ? "null"
                        : value.isObject()
                                ? "object"
                                : value.isArray()
                                        ? "array"
                                        : value.isTextual()
                                                ? "string"
                                                : value.isBoolean()
                                                        ? "boolean"
                                                        : value.isIntegralNumber()
                                                                ? "integer"
                                                                : "number";
        assertThat(types).as(path + " must accept actual JSON type " + actual).contains(actual);
        if (value.isNull()) return;
        if (schema.has("enum")) assertThat(schema.path("enum")).as(path + " enum").contains(value);
        if (value.isTextual() && schema.path("format").asText().equals("date-time"))
            assertThatCode(() -> Instant.parse(value.asText())).as(path).doesNotThrowAnyException();
        if (value.isObject()) {
            for (var required : schema.path("required"))
                assertThat(value.has(required.asText())).as(path).isTrue();
            var fields = value.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                accepts(
                        doc,
                        schema.path("properties").path(field.getKey()),
                        field.getValue(),
                        path + "/" + field.getKey());
            }
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++)
                accepts(doc, schema.path("items"), value.get(i), path + "/" + i);
        }
    }

    @Test
    void noHistoryMemberNullDatesAreAcceptedButOfferProjectionRejectsNull() throws Exception {
        member(6);
        var doc = document();
        var payload = me();
        assertThat(payload.path("startsAt").isNull()).isTrue();
        assertThat(payload.path("expiresAt").isNull()).isTrue();
        var schema = data(doc, "/membership/me");
        accepts(doc, schema, payload, "/membership/me");
        var offer = resolve(doc, schema.at("/properties/plans/items"));
        assertThatThrownBy(
                        () ->
                                accepts(
                                        doc,
                                        offer.at("/properties/projectedExpiresAt"),
                                        json.nullNode(),
                                        "projectedExpiresAt"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void fullyRevokedMemberAndAdminRecordNullDatesMatchTheirResolvedSchemas() throws Exception {
        String order = paid(member(6), "2026-10-10T02:00:00Z");
        deliver(order);
        refund(order, true, "schema-revoke-" + order);
        var doc = document();
        var member = me();
        assertThat(member.path("state").asText()).isEqualTo("NONE");
        assertThat(member.path("startsAt").isNull()).isTrue();
        assertThat(member.path("expiresAt").isNull()).isTrue();
        var records = call("GET", "/admin/membership/records?page=1&pageSize=1", a, null, 200);
        var record = records.path("items").get(0);
        assertThat(record.path("userId").asText()).isEqualTo(Long.toString(uid));
        assertThat(record.path("startsAt").isNull()).isTrue();
        assertThat(record.path("expiresAt").isNull()).isTrue();
        var softly = new SoftAssertions();
        softly.assertThatCode(
                        () -> accepts(doc, data(doc, "/membership/me"), member, "/membership/me"))
                .doesNotThrowAnyException();
        softly.assertThatCode(
                        () ->
                                accepts(
                                        doc,
                                        data(doc, "/admin/membership/records"),
                                        records,
                                        "/admin/membership/records"))
                .doesNotThrowAnyException();
        softly.assertAll();
    }

    @Test
    void permanentLearningNullExpiryMatchesTheResolvedLearningItemSchema() throws Exception {
        var course = course("PERMANENT", null);
        deliver(paid(course.path("productId").asText(), "2026-10-10T02:00:00Z"));
        var payload = call("GET", "/learning/courses", b, null, 200);
        var item = payload.path("items").get(0);
        assertThat(item.path("courseId").asText()).isEqualTo(course.path("id").asText());
        assertThat(item.path("expiresAt").isNull()).isTrue();
        var doc = document();
        accepts(doc, data(doc, "/learning/courses"), payload, "/learning/courses");
    }
}
