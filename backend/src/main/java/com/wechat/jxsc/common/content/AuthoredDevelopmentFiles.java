package com.wechat.jxsc.common.content;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Original, deterministic development material. No third-party content. */
final class AuthoredDevelopmentFiles {
    private AuthoredDevelopmentFiles() {}

    static byte[] png() throws IOException {
        var image =
                new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++) image.setRGB(x, y, (x + y) % 2 == 0 ? 0x215574 : 0xe9f1f5);
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    static byte[] pdf() throws IOException {
        String content =
                "BT /F1 12 Tf 40 100 Td (DEV - Self authored practice: write one sentence.) Tj ET";
        List<String> objects =
                List.of(
                        "<< /Type /Catalog /Pages 2 0 R >>",
                        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Resources << /Font"
                                + " << /F1 4 0 R >> >> /Contents 5 0 R >>",
                        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                        "<< /Length "
                                + content.length()
                                + " >>\nstream\n"
                                + content
                                + "\nendstream");
        var out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            out.write(
                    ((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n")
                            .getBytes(StandardCharsets.US_ASCII));
        }
        int xref = out.size();
        out.write(("xref\n0 6\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets)
            out.write(
                    String.format(Locale.ROOT, "%010d 00000 n \n", offset)
                            .getBytes(StandardCharsets.US_ASCII));
        out.write(
                ("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n")
                        .getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    static byte[] docx() throws IOException {
        var parts = new LinkedHashMap<String, String>();
        parts.put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                    + " Extension=\"rels\""
                    + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                    + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                    + " PartName=\"/word/document.xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
        parts.put(
                "_rels/.rels",
                "<?xml version=\"1.0\"?><Relationships"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                    + " Id=\"rId1\""
                    + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                    + " Target=\"word/document.xml\"/></Relationships>");
        parts.put(
                "word/document.xml",
                "<?xml version=\"1.0\"?><w:document"
                    + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>DEV"
                    + " - Self authored practice. Write one"
                    + " sentence.</w:t></w:r></w:p><w:sectPr/></w:body></w:document>");
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var part : parts.entrySet()) {
                var entry = new ZipEntry(part.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
