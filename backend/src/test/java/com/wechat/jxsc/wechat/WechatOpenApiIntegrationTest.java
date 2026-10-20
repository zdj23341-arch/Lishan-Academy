package com.wechat.jxsc.wechat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.*;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.*;

@AutoConfigureMockMvc
class WechatOpenApiIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    JsonNode resolve(JsonNode doc, JsonNode node) {
        return node.has("$ref") ? doc.at(node.path("$ref").asText().substring(1)) : node;
    }

    JsonNode endpointData(JsonNode doc, String path) {
        var content =
                doc.path("paths")
                        .path(path)
                        .path("get")
                        .path("responses")
                        .path("200")
                        .path("content");
        var response = resolve(doc, content.elements().next().path("schema"));
        return resolve(doc, response.path("properties").path("data"));
    }

    @Test
    void issueSchemasAndWebhookWireContractsResolveCorrectly() throws Exception {
        var doc =
                json.readTree(
                        mvc.perform(get("/v3/api-docs"))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var page = endpointData(doc, "/api/v1/admin/wechat/reconciliation-issues");
        var item = resolve(doc, page.path("properties").path("items").path("items"));
        assertThat(item.path("properties").properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "id",
                        "channel",
                        "businessNo",
                        "resourceType",
                        "issueCode",
                        "billDate",
                        "localAmountFen",
                        "channelAmountFen",
                        "status");
        for (String money : new String[] {"localAmountFen", "channelAmountFen"})
            assertThat(item.path("properties").path(money).toString()).contains("null");
        assertThat(
                        doc.path("components")
                                .path("schemas")
                                .path("Issue")
                                .path("properties")
                                .has("transactionId"))
                .isTrue();
        for (String kind : new String[] {"payments", "refunds", "transfers"}) {
            var op = doc.path("paths").path("/api/v1/webhooks/wechat/" + kind).path("post");
            assertThat(op.path("responses").has("204")).isTrue();
            assertThat(op.path("responses").has("200")).isFalse();
            assertThat(op.path("responses").path("204").has("content")).isFalse();
            assertThat(
                            op.path("requestBody")
                                    .path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("type")
                                    .asText())
                    .isEqualTo("object");
            for (String header :
                    new String[] {
                        "Wechatpay-Serial",
                        "Wechatpay-Signature",
                        "Wechatpay-Timestamp",
                        "Wechatpay-Nonce"
                    }) {
                var matches = new java.util.ArrayList<JsonNode>();
                op.path("parameters")
                        .forEach(
                                p -> {
                                    if (p.path("name").asText().equals(header)) matches.add(p);
                                });
                assertThat(matches).hasSize(1);
                assertThat(matches.getFirst().path("required").asBoolean()).isTrue();
                assertThat(matches.getFirst().path("in").asText()).isEqualTo("header");
            }
        }
    }

    @Test
    void publicCallbacksAndLoginAndProtectedOperationsAreDocumented() throws Exception {
        var doc =
                json.readTree(
                        mvc.perform(get("/v3/api-docs"))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(doc.path("info").path("version").asText()).isEqualTo("stage-5b");
        for (String path :
                new String[] {
                    "/api/v1/auth/wechat-login",
                    "/api/v1/webhooks/wechat/payments",
                    "/api/v1/webhooks/wechat/refunds",
                    "/api/v1/webhooks/wechat/transfers"
                }) assertThat(doc.path("paths").path(path).path("post").path("security")).isEmpty();
        assertThat(
                        doc.path("paths")
                                .path("/api/v1/admin/wechat/reconciliation-issues")
                                .path("get")
                                .path("security")
                                .toString())
                .contains("bearerAuth");
        assertThat(
                        doc.path("components")
                                .path("schemas")
                                .path("MerchantTransferParameters")
                                .path("properties")
                                .has("package"))
                .isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc)
                .set(
                        "servers",
                        json.createArrayNode()
                                .add(json.createObjectNode().put("url", "http://localhost:8080")));
        Files.writeString(
                Path.of("target/openapi-stage5b.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n");
    }
}
