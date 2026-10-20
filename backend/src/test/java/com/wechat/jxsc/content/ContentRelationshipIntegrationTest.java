package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.*;

class ContentRelationshipIntegrationTest extends ContentHttpSupport {
    @Test
    void paperAttachmentOnlyEditsAdvanceVersionAndPreventStaleOverwrite() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var asset = upload(token, "PAPER_ATTACHMENT", "paper.png", "image/png", png());
        var body = new LinkedHashMap<String, Object>();
        body.put("version", 0);
        body.put("title", "Relationship paper");
        body.put("stage", "JUNIOR");
        body.put("grade", "9");
        body.put("subject", "CHINESE");
        body.put("status", "PUBLISHED");
        body.put("contentBlocks", List.of());
        body.put(
                "attachments",
                List.of(
                        Map.of(
                                "assetId",
                                asset.path("id").asText(),
                                "title",
                                "Original attachment",
                                "sortNo",
                                0)));
        var paper = call("POST", "/admin/papers", token, body, 200);
        body.put(
                "attachments",
                List.of(
                        Map.of(
                                "assetId",
                                asset.path("id").asText(),
                                "title",
                                "Revised attachment",
                                "sortNo",
                                0)));
        var edited = call("PATCH", "/admin/papers/" + paper.path("id").asText(), token, body, 200);
        assertThat(edited.path("version").asLong()).isEqualTo(1);
        call("PATCH", "/admin/papers/" + paper.path("id").asText(), token, body, 409);
    }

    @Test
    void completionRejectsClientSuppliedReadinessAndDocxRequiresRealDocumentRoots()
            throws Exception {
        String token = admin("CONTENT_EDITOR");
        var ticket =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "purpose",
                                "PUBLIC_IMAGE",
                                "name",
                                "x.png",
                                "mime",
                                "image/png",
                                "bytes",
                                png().length),
                        200);
        putBytes(token, ticket, png(), 200);
        call(
                "POST",
                "/admin/files/" + ticket.path("id").asText() + "/complete",
                token,
                Map.of("status", "READY", "bytes", 0),
                400);
        call(
                "POST",
                "/admin/files/" + ticket.path("id").asText() + "/complete",
                token,
                Map.of(),
                200);
    }

    @Test
    void docxMustContainWordDocumentXmlInsteadOfArbitraryXml() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            for (String entry : List.of("[Content_Types].xml", "word/document.xml")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry));
                zip.write(
                        "<not-a-word-document/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var fake =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "purpose",
                                "PAPER_ATTACHMENT",
                                "name",
                                "wrong.docx",
                                "mime",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "bytes",
                                out.size()),
                        200);
        putBytes(token, fake, out.toByteArray(), 200);
        call(
                "POST",
                "/admin/files/" + fake.path("id").asText() + "/complete",
                token,
                Map.of(),
                422);
    }
}
