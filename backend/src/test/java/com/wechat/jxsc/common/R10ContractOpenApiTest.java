package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;

@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class R10ContractOpenApiTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void exportsActualAdminFiltersAndIndependentInventoryContract() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var doc = json.readTree(response.getContentAsByteArray());
        parameters(doc, "/api/v1/admin/products", "keyword", "type", "status", "page", "pageSize");
        parameters(doc, "/api/v1/admin/categories", "keyword", "type", "enabled", "page", "pageSize");
        parameters(doc, "/api/v1/admin/articles", "keyword", "status", "page", "pageSize");
        parameters(doc, "/api/v1/admin/inventory", "keyword", "page", "pageSize");
        assertThat(doc.path("paths").path("/api/v1/admin/inventory").path("get").path("security")).isNotEmpty();
        assertThat(doc.path("paths").path("/api/v1/admin/products/{id}/inventory").path("get").path("security")).isNotEmpty();
        var schemas = doc.path("components").path("schemas");
        for (String field : List.of("quantityLimit", "shippingSummary", "physicalStock", "lockedStock", "availableStock", "inventoryVersion"))
            assertThat(schemas.path("CatalogBookAdmin").path("properties").has(field)).as(field).isTrue();
        assertThat(schemas.path("InventoryAdjustment").path("properties").has("version")).isTrue();
        assertThat(schemas.path("InventoryView").path("properties").has("version")).isTrue();
        for (String field : List.of("productId", "title", "type", "status", "version", "onHand", "reserved", "availableStock"))
            assertThat(schemas.path("InventoryItem").path("properties").has(field)).as(field).isTrue();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi-r10-backend.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n");
    }

    private void parameters(JsonNode doc, String path, String... expected) {
        var names = new ArrayList<String>();
        for (var value : doc.path("paths").path(path).path("get").path("parameters")) names.add(value.path("name").asText());
        assertThat(names).as(path).contains(expected);
    }
}
