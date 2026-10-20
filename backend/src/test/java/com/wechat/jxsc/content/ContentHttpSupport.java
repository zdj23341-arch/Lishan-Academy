package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.support.MySqlIntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.*;

@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
public abstract class ContentHttpSupport extends MySqlIntegrationTest {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    // Independent scenarios must not accumulate against the same production IP bucket.
    // Authentication/rate-limit suites keep their deliberate fixed-IP boundary requests.
    private final String testRemote = "test-client-" + UUID.randomUUID();

    protected String admin(String role) throws Exception {
        String name = "content-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " admin_user(login_name,password_hash,status,password_change_required,created_at,updated_at,version)"
                    + " VALUES (?,?,'ACTIVE',false,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                name,
                new BCryptPasswordEncoder(4).encode("Content-Test-Pass-42!"));
        jdbc.update(
                "INSERT INTO admin_user_role(admin_user_id,role_id) SELECT u.id,r.id FROM"
                        + " admin_user u,admin_role r WHERE u.login_name=? AND r.code=?",
                name,
                role);
        return call(
                        "POST",
                        "/admin/auth/login",
                        null,
                        Map.of("loginName", name, "password", "Content-Test-Pass-42!"),
                        200)
                .path("accessToken")
                .asText();
    }

    protected String buyer(String fixture) throws Exception {
        return call("POST", "/auth/dev-login", null, Map.of("fixture", fixture), 200)
                .path("accessToken")
                .asText();
    }

    protected JsonNode call(String method, String path, String token, Object body, int status)
            throws Exception {
        var req =
                MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(
                                request -> {
                                    request.setRemoteAddr(testRemote);
                                    return request;
                                });
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.content(json.writeValueAsBytes(body));
        var response = mvc.perform(req).andReturn().getResponse();
        assertThat(response.getStatus())
                .as(method + " " + path + " " + response.getContentAsString())
                .isEqualTo(status);
        var result = json.readTree(response.getContentAsByteArray());
        assertThat(result.path("requestId").asText()).isNotBlank();
        return status == 200 ? result.path("data") : result;
    }

    protected Map<String, Object> product(String type, String title, long price) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("version", 0);
        b.put("type", type);
        b.put("title", title);
        b.put("priceFen", price);
        b.put("categoryIds", List.of());
        b.put("contentBlocks", List.of());
        b.put("sortNo", 0);
        if (type.equals("BOOK"))
            b.put(
                    "book",
                    Map.of(
                            "quantityLimit",
                            9,
                            "shippingSummary",
                            "DEV shipping configuration required"));
        if (type.equals("MEMBERSHIP")) b.put("membership", Map.of("durationMonths", 6));
        return b;
    }

    protected JsonNode createProduct(String token, String type, String title, long price)
            throws Exception {
        return call("POST", "/admin/products", token, product(type, title, price), 200);
    }

    protected JsonNode publish(String token, JsonNode product) throws Exception {
        return call(
                "POST",
                "/admin/products/" + product.path("id").asText() + "/publish",
                token,
                Map.of(
                        "version",
                        product.path("version").asLong(),
                        "reason",
                        "Publish test content"),
                200);
    }

    protected byte[] png() throws Exception {
        var b = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                b);
        return b.toByteArray();
    }

    protected JsonNode upload(String token, String purpose, String name, String mime, byte[] bytes)
            throws Exception {
        var ticket =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "purpose",
                                purpose,
                                "name",
                                name,
                                "mime",
                                mime,
                                "bytes",
                                bytes.length),
                        200);
        putBytes(token, ticket, bytes, 200);
        return call(
                "POST",
                "/admin/files/" + ticket.path("id").asText() + "/complete",
                token,
                Map.of(),
                200);
    }

    protected void putBytes(String token, JsonNode ticket, byte[] bytes, int expected)
            throws Exception {
        var result =
                mvc.perform(
                                MockMvcRequestBuilders.put(ticket.path("uploadUrl").asText())
                                        .header("Authorization", "Bearer " + token)
                                        .header(
                                                "X-Upload-Token",
                                                ticket.path("uploadToken").asText())
                                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                        .content(bytes))
                        .andReturn()
                        .getResponse();
        assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(expected);
    }
}
