package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

@AutoConfigureMockMvc
class OpenApiExportTest extends MySqlIntegrationTest {
    @Test
    void analyticsContractDocumentsPeriodCurrentSnapshotAndExplicitDevScope() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var doc = json.readTree(result.getResponse().getContentAsByteArray());
        var overview = responseData(doc, "/api/v1/admin/analytics/overview", "get");
        var metrics = resolve(doc, overview.at("/properties/periodMetrics"));
        var current = resolve(doc, overview.at("/properties/currentMetrics"));
        assertThat(metrics.path("properties").has("orderCreatedCount")).isTrue();
        assertThat(metrics.path("properties").has("netCashAmountFen")).isTrue();
        assertThat(current.path("properties").has("waitingShipmentOrderCount")).isTrue();
        assertThat(overview.path("properties").has("generatedAt")).isTrue();
        assertThat(overview.path("properties").has("environment")).isTrue();
        assertThat(overview.path("properties").has("receiptsFen")).isTrue();
        var trend = responseData(doc, "/api/v1/admin/analytics/trend", "get");
        assertThat(trend.path("properties").has("period")).isTrue();
        var item = resolve(doc, trend.at("/properties/items/items"));
        assertThat(item.path("properties").has("date")).isTrue();
        assertThat(item.path("properties").has("refundedAmountFen")).isTrue();
        for (String endpoint : java.util.List.of("overview", "trend")) {
            boolean found = false;
            for (var parameter :
                    doc.path("paths")
                            .path("/api/v1/admin/analytics/" + endpoint)
                            .path("get")
                            .path("parameters"))
                if (parameter.path("name").asText().equals("includeDevChannel")) {
                    found = true;
                    assertThat(parameter.at("/schema/default").asBoolean()).isFalse();
                }
            assertThat(found).isTrue();
        }
    }

    @Test
    void commerceResponseSchemasRemainDistinctAndCreatesHaveTypedData() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var doc = json.readTree(result.getResponse().getContentAsByteArray());
        var order = responseData(doc, "/api/v1/orders/{id}", "get");
        var payment = responseData(doc, "/api/v1/payments/wechat/prepay", "post");
        var create = responseData(doc, "/api/v1/orders", "post");
        assertThat(order.path("properties").has("orderStatus")).isTrue();
        assertThat(payment.path("properties").has("provider")).isTrue();
        assertThat(create.path("properties").has("orderStatus")).isTrue();
        assertThat(order.path("properties").has("financialHold")).isTrue();
        assertThat(order.at("/properties/paidAmountFen/type").asText()).isEqualTo("integer");
        assertThat(order.at("/properties/refundedAmountFen/type").asText()).isEqualTo("integer");
        var item = resolve(doc, order.path("properties").path("item"));
        assertThat(item.path("properties").has("fulfillableQuantity")).isTrue();
        assertThat(item.path("properties").has("shippedQuantity")).isTrue();
        assertThat(item.path("properties").has("coverUrl")).isTrue();
        var reconciliation = resolve(doc, order.path("properties").path("reconciliation"));
        assertThat(reconciliation.path("properties").has("confirmedRefundFen")).isTrue();
        assertThat(reconciliation.path("properties").has("possibleInFlightRefundFen")).isTrue();
    }

    private com.fasterxml.jackson.databind.JsonNode responseData(
            com.fasterxml.jackson.databind.JsonNode doc, String path, String method) {
        var content =
                doc.path("paths")
                        .path(path)
                        .path(method)
                        .path("responses")
                        .path("200")
                        .path("content")
                        .elements()
                        .next()
                        .path("schema");
        var envelope = resolve(doc, content);
        return resolve(doc, envelope.path("properties").path("data"));
    }

    private com.fasterxml.jackson.databind.JsonNode resolve(
            com.fasterxml.jackson.databind.JsonNode doc,
            com.fasterxml.jackson.databind.JsonNode schema) {
        return schema.has("$ref") ? doc.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void exportsImplementedStageFourRoutesAndBearerSecurity() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = (ObjectNode) json.readTree(result.getResponse().getContentAsByteArray());
        var paths = new ArrayList<String>();
        document.path("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths)
                .containsExactlyInAnyOrder(
                        "/api/v1/admin/content-candidates",
                        "/api/v1/admin/course-workflows",
                        "/api/v1/admin/course-workflows/{courseId}",
                        "/api/v1/admin/course-workflows/{courseId}/publish",
                        "/api/v1/admin/course-workflows/{courseId}/chapters",
                        "/api/v1/admin/course-workflows/operations/{operationKey}",
                        "/api/v1/admin/home/sections/{sectionId}/recommend",
                        "/api/v1/me/close",
                        "/api/v1/admin/retained-personal-data/{userId}/read",
                        "/api/v1/auth/wechat-login",
                        "/api/v1/distribution/receipt-profile",
                        "/api/v1/webhooks/wechat/payments",
                        "/api/v1/webhooks/wechat/refunds",
                        "/api/v1/webhooks/wechat/transfers",
                        "/api/v1/admin/wechat/reconciliation-issues",
                        "/api/v1/admin/wechat/statements/{date}/refresh",
                        "/api/v1/distribution/withdrawals/{id}/cancel",
                        "/api/v1/health/ready",
                        "/api/v1/admin/runtime",
                        "/api/v1/admin/orders/{id}/reconcile",
                        "/api/v1/admin/orders/{id}/economic-resolutions",
                        "/api/v1/admin/withdrawals/{id}/reconcile",
                        "/api/v1/referrals/resolve",
                        "/api/v1/referrals/claim",
                        "/api/v1/distribution/summary",
                        "/api/v1/distribution/commissions",
                        "/api/v1/distribution/referral-codes",
                        "/api/v1/referrals/{code}/image",
                        "/api/v1/distribution/withdrawals",
                        "/api/v1/distribution/withdrawals/{id}",
                        "/api/v1/distribution/withdrawals/{id}/refresh",
                        "/api/v1/admin/distributors",
                        "/api/v1/admin/distributors/{id}",
                        "/api/v1/admin/products/{id}/distributors",
                        "/api/v1/admin/commissions",
                        "/api/v1/admin/settings",
                        "/api/v1/admin/withdrawals",
                        "/api/v1/admin/withdrawals/{id}",
                        "/api/v1/admin/withdrawals/{id}/refresh",
                        "/api/v1/admin/analytics/overview",
                        "/api/v1/admin/analytics/trend",
                        "/api/v1/admin/exports",
                        "/api/v1/admin/exports/{id}",
                        "/api/v1/admin/exports/{id}/content",
                        "/api/v1/dev/withdrawals/{id}/simulate",
                        "/api/v1/health",
                        "/api/v1/courses/{id}/chapters/{chapterId}/play-url",
                        "/api/v1/learning/chapters/{chapterId}/progress",
                        "/api/v1/learning/courses",
                        "/api/v1/membership/me",
                        "/api/v1/admin/membership/records",
                        "/api/v1/papers/{id}/files/{fileId}/authorize",
                        "/api/v1/protected-content/{token}",
                        "/api/v1/auth/dev-login",
                        "/api/v1/auth/logout",
                        "/api/v1/me",
                        "/api/v1/admin/auth/login",
                        "/api/v1/admin/auth/logout",
                        "/api/v1/admin/auth/change-password",
                        "/api/v1/admin/me",
                        "/api/v1/admin/audit-logs",
                        "/api/v1/operations/{key}",
                        "/api/v1/config/public",
                        "/api/v1/home",
                        "/api/v1/categories",
                        "/api/v1/products",
                        "/api/v1/products/{id}",
                        "/api/v1/membership/plans",
                        "/api/v1/papers",
                        "/api/v1/papers/{id}",
                        "/api/v1/articles/{id}",
                        "/api/v1/courses/{id}/chapters",
                        "/api/v1/assets/{id}/content",
                        "/api/v1/admin/products",
                        "/api/v1/admin/products/{id}",
                        "/api/v1/admin/products/{id}/publish",
                        "/api/v1/admin/products/{id}/unpublish",
                        "/api/v1/admin/categories",
                        "/api/v1/admin/categories/{id}",
                        "/api/v1/admin/membership/plans/{id}",
                        "/api/v1/admin/courses",
                        "/api/v1/admin/courses/{id}",
                        "/api/v1/admin/courses/{id}/chapters",
                        "/api/v1/admin/courses/{id}/chapter-order",
                        "/api/v1/admin/chapters/{id}",
                        "/api/v1/admin/papers",
                        "/api/v1/admin/papers/{id}",
                        "/api/v1/admin/articles",
                        "/api/v1/admin/articles/{id}",
                        "/api/v1/admin/home/draft",
                        "/api/v1/admin/home/preview",
                        "/api/v1/admin/home/publish",
                        "/api/v1/admin/files/presign",
                        "/api/v1/admin/files/{id}/complete",
                        "/api/v1/files/presign",
                        "/api/v1/files/{id}/complete",
                        "/api/v1/local-uploads/{id}",
                        "/api/v1/admin/files",
                        "/api/v1/admin/files/{id}",
                        "/api/v1/admin/membership/plans",
                        "/api/v1/addresses",
                        "/api/v1/addresses/{id}",
                        "/api/v1/orders",
                        "/api/v1/orders/preview",
                        "/api/v1/orders/{id}",
                        "/api/v1/orders/{id}/cancel",
                        "/api/v1/orders/{id}/confirm-receipt",
                        "/api/v1/orders/{id}/refund",
                        "/api/v1/orders/{id}/logistics",
                        "/api/v1/shipments/{orderId}",
                        "/api/v1/payments/wechat/prepay",
                        "/api/v1/dev/orders/{id}/pay",
                        "/api/v1/dev/aftersales/{id}/refund",
                        "/api/v1/aftersales/{id}",
                        "/api/v1/aftersales/{id}/return-shipment",
                        "/api/v1/aftersales/{id}/evidence/{assetId}/content",
                        "/api/v1/admin/orders",
                        "/api/v1/admin/orders/{id}",
                        "/api/v1/admin/orders/{id}/ship",
                        "/api/v1/admin/orders/{id}/refund",
                        "/api/v1/admin/aftersales",
                        "/api/v1/admin/aftersales/{id}",
                        "/api/v1/admin/aftersales/{id}/preview-decision",
                        "/api/v1/admin/aftersales/{id}/approve",
                        "/api/v1/admin/aftersales/{id}/reject",
                        "/api/v1/admin/aftersales/{id}/receive-return",
                        "/api/v1/admin/aftersales/{id}/evidence/{assetId}/content",
                        "/api/v1/admin/shipping-rules",
                        "/api/v1/admin/shipping-rules/{provinceCode}",
                        "/api/v1/admin/products/{id}/inventory-adjustments");
        assertThat(
                        document.path("components")
                                .path("securitySchemes")
                                .path("bearerAuth")
                                .path("scheme")
                                .asText())
                .isEqualTo("bearer");
        assertThat(
                        document.path("paths")
                                .path("/api/v1/me")
                                .path("get")
                                .path("security")
                                .toString())
                .contains("bearerAuth");
        assertThat(
                        document.path("paths")
                                .path("/api/v1/referrals/{code}/image")
                                .path("get")
                                .path("security"))
                .isEmpty();
        assertThat(
                        document.path("paths")
                                .path("/api/v1/auth/dev-login")
                                .path("post")
                                .path("security"))
                .isEmpty();
        document.set(
                "servers",
                json.createArrayNode()
                        .add(json.createObjectNode().put("url", "http://localhost:8080")));
        Files.createDirectories(Path.of("target"));
        Files.writeString(
                Path.of("target/openapi-stage4.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }
}
