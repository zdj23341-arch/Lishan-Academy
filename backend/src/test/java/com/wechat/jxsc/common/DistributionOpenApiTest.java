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
class DistributionOpenApiTest extends MySqlIntegrationTest {
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
                        .at("/responses/200/content/*~1*/schema");
        if (schema.isMissingNode())
            schema =
                    doc.path("paths")
                            .path("/api/v1" + path)
                            .path(method)
                            .at("/responses/200/content/application~1json/schema");
        return resolve(doc, resolve(doc, schema).at("/properties/data"));
    }

    @Test
    void withdrawalSchemasPreserveMoneyNullableConfirmationAndPublicResolve() throws Exception {
        var doc =
                json.readTree(
                        mvc.perform(get("/v3/api-docs"))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var w = data(doc, "/distribution/withdrawals/{id}", "get");
        assertThat(w.at("/properties/amountFen/type").asText()).isEqualTo("integer");
        assertThat(w.at("/properties/id/type").asText()).isEqualTo("string");
        for (String field : java.util.List.of("withdrawalNo", "successAt", "failReason"))
            assertThat(w.path("properties").has(field)).as(field).isTrue();
        assertThat(
                        doc.at("/components/schemas/TransferConfirmation/properties/action/type")
                                .asText())
                .isEqualTo("string");
        JsonNode confirmation = w.at("/properties/confirmation");
        boolean nullable =
                confirmation.path("type").toString().contains("null")
                        || confirmation.path("anyOf").toString().contains("null")
                        || resolve(doc, confirmation).path("type").toString().contains("null");
        assertThat(nullable).as("Actual null confirmation must be valid under OAS3.1").isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1referrals~1resolve/post/security").size()).isZero();
        var commission =
                resolve(
                        doc,
                        data(doc, "/distribution/commissions", "get")
                                .at("/properties/items/items"));
        assertThat(commission.at("/properties/rateBps/type").asText()).isEqualTo("integer");
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/openapi-stage4.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }
}
