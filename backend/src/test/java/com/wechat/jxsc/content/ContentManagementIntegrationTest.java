package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.*;

class ContentManagementIntegrationTest extends ContentHttpSupport {
    @Test
    void disabledPrivateAttachmentsAreOmittedFromPublishedMetadata() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var asset = upload(token, "PAPER_ATTACHMENT", "retired.png", "image/png", png());
        var paper =
                call(
                        "POST",
                        "/admin/papers",
                        token,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "Retired attachment",
                                "stage",
                                "JUNIOR",
                                "grade",
                                "9",
                                "subject",
                                "CHINESE",
                                "status",
                                "PUBLISHED",
                                "contentBlocks",
                                List.of(),
                                "attachments",
                                List.of(
                                        Map.of(
                                                "assetId",
                                                asset.path("id").asText(),
                                                "title",
                                                "Retired",
                                                "sortNo",
                                                0))),
                        200);
        var detail = call("GET", "/admin/files/" + asset.path("id").asText(), token, null, 200);
        call(
                "PATCH",
                "/admin/files/" + asset.path("id").asText(),
                token,
                Map.of(
                        "version",
                        detail.path("version").asLong(),
                        "status",
                        "DISABLED",
                        "reason",
                        "Retired private content"),
                200);
        assertThat(
                        call("GET", "/papers/" + paper.path("id").asText(), null, null, 200)
                                .path("attachments"))
                .isEmpty();
    }

    @Test
    void ownersCanListAndDisableFilesWithVersionAuditWithoutExposingEvidence() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var file = upload(token, "PUBLIC_IMAGE", "disable.png", "image/png", png());
        String id = file.path("id").asText();
        var files = call("GET", "/admin/files", token, null, 200);
        assertThat(files.path("items").toString()).contains(id);
        var detail = call("GET", "/admin/files/" + id, token, null, 200);
        assertThat(detail.path("version").isIntegralNumber()).isTrue();
        call("GET", "/admin/files/" + id, admin("ORDER_OPERATOR"), null, 403);
        call("GET", "/admin/files/" + id, admin("CONTENT_EDITOR"), null, 404);
        var patch =
                Map.of(
                        "version",
                        detail.path("version").asLong(),
                        "status",
                        "DISABLED",
                        "reason",
                        "Retire this public image");
        call("PATCH", "/admin/files/" + id, token, patch, 200);
        call("PATCH", "/admin/files/" + id, token, patch, 409);
        call("GET", "/assets/" + id + "/content", null, null, 409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM operation_audit_log WHERE"
                                        + " resource_type='FILE_ASSET' AND resource_id=? AND"
                                        + " action='ASSET_DISABLE'",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void adminsCanListUnpublishedMembershipPlans() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var p = createProduct(token, "MEMBERSHIP", "Draft membership plan", 1200);
        var plans = call("GET", "/admin/membership/plans", token, null, 200);
        assertThat(plans.path("items").toString())
                .contains(p.path("membership").path("id").asText(), "DISABLED");
    }

    @Test
    void generatedSchemasKeepEachResourcesDistinctResponseFields() throws Exception {
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/v3/api-docs"))
                        .andReturn()
                        .getResponse();
        var doc = json.readTree(response.getContentAsByteArray());
        for (var entry :
                Map.of(
                                "/api/v1/articles/{id}",
                                List.of("contentBlocks", "publishedAt"),
                                "/api/v1/papers/{id}",
                                List.of("attachments", "visibility"),
                                "/api/v1/admin/courses/{id}",
                                List.of("validityType", "productId"))
                        .entrySet()) {
            var envelopeRef =
                    doc.path("paths")
                            .path(entry.getKey())
                            .path("get")
                            .path("responses")
                            .path("200")
                            .path("content")
                            .path("*/*")
                            .path("schema")
                            .path("$ref")
                            .asText();
            if (envelopeRef.isEmpty())
                envelopeRef =
                        doc.path("paths")
                                .path(entry.getKey())
                                .path("get")
                                .path("responses")
                                .path("200")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText();
            var envelope = doc.at(envelopeRef.substring(1));
            var dataRef = envelope.path("properties").path("data").path("$ref").asText();
            var properties = doc.at(dataRef.substring(1)).path("properties");
            for (String expected : entry.getValue())
                assertThat(properties.has(expected))
                        .as(entry.getKey() + " must describe " + expected)
                        .isTrue();
        }
    }
}
