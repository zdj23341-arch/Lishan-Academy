package com.wechat.jxsc.common.storage;

import org.apache.pdfbox.Loader;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

/** Structural format checks only; this is NOT an antivirus. Enabled only in dev/test. */
public final class DevelopmentFormatScanner implements FormatScanner {
    public String status() {
        return "FORMAT_VALIDATED_DEV_ONLY";
    }

    public boolean accepts(String ext, InputStream source) throws IOException {
        try {
            if (Set.of("PNG", "JPG", "JPEG").contains(ext)) {
                try (var input = ImageIO.createImageInputStream(source)) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) return false;
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        String actual = reader.getFormatName().toUpperCase(Locale.ROOT);
                        if (!(ext.equals(actual)
                                || (Set.of("JPG", "JPEG").contains(ext) && actual.equals("JPEG"))))
                            return false;
                        if ((long) reader.getWidth(0) * reader.getHeight(0) > 25000000L)
                            return false;
                        return reader.read(0) != null;
                    } finally {
                        reader.dispose();
                    }
                }
            }
            if (ext.equals("PDF")) {
                try (var doc = Loader.loadPDF(source.readNBytes(52428801))) {
                    return !doc.isEncrypted() && doc.getNumberOfPages() > 0;
                }
            }
            if (ext.equals("DOC")) {
                try (var fs = new POIFSFileSystem(source)) {
                    return fs.getRoot().hasEntry("WordDocument")
                            && (fs.getRoot().hasEntry("0Table") || fs.getRoot().hasEntry("1Table"));
                }
            }
            if (ext.equals("DOCX")) {
                Set<String> required =
                        new HashSet<>(Set.of("[Content_Types].xml", "word/document.xml"));
                long total = 0;
                int entries = 0;
                try (var zip = new ZipInputStream(source)) {
                    ZipEntry e;
                    while ((e = zip.getNextEntry()) != null) {
                        if (++entries > 1000 || e.getName().contains("..")) return false;
                        byte[] b = zip.readNBytes(10485761);
                        total += b.length;
                        if (b.length > 10485760 || total > 52428800) return false;
                        if (required.remove(e.getName())) {
                            var factory = DocumentBuilderFactory.newInstance();
                            factory.setFeature(
                                    "http://apache.org/xml/features/disallow-doctype-decl", true);
                            factory.setFeature(
                                    "http://xml.org/sax/features/external-general-entities", false);
                            factory.setFeature(
                                    "http://xml.org/sax/features/external-parameter-entities",
                                    false);
                            factory.setNamespaceAware(true);
                            factory.setXIncludeAware(false);
                            factory.setExpandEntityReferences(false);
                            var root =
                                    factory.newDocumentBuilder()
                                            .parse(new ByteArrayInputStream(b))
                                            .getDocumentElement();
                            if (e.getName().equals("word/document.xml")) {
                                if (!root.getLocalName().equals("document")
                                        || !"http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                                                .equals(root.getNamespaceURI())) return false;
                            } else if (!root.getLocalName().equals("Types")
                                    || !"http://schemas.openxmlformats.org/package/2006/content-types"
                                            .equals(root.getNamespaceURI())) return false;
                        }
                    }
                }
                return required.isEmpty();
            }
            // WEBP/MP4 structural container validation is bounded and does not claim
            // codec/transcode support.
            if (ext.equals("WEBP")) return webp(source);
            if (ext.equals("MP4")) return mp4(source);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean webp(InputStream source) throws IOException {
        byte[] b = source.readNBytes(10485761);
        if (b.length < 30
                || b.length > 10485760
                || !ascii(b, 0, 4).equals("RIFF")
                || !ascii(b, 8, 4).equals("WEBP")) return false;
        long size =
                Integer.toUnsignedLong(
                        java.nio.ByteBuffer.wrap(b, 4, 4)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .getInt());
        if (size + 8 != b.length) return false;
        int cursor = 12;
        boolean image = false;
        while (cursor + 8 <= b.length) {
            String type = ascii(b, cursor, 4);
            long len =
                    Integer.toUnsignedLong(
                            java.nio.ByteBuffer.wrap(b, cursor + 4, 4)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .getInt());
            long end = cursor + 8L + len;
            if (end > b.length) return false;
            int data = cursor + 8;
            if (type.equals("VP8 "))
                image =
                        len > 10
                                && (b[data] & 1) == 0
                                && (b[data + 3] & 255) == 0x9d
                                && (b[data + 4] & 255) == 0x01
                                && (b[data + 5] & 255) == 0x2a;
            if (type.equals("VP8L")) image = len > 5 && (b[data] & 255) == 0x2f;
            cursor = (int) (end + (len & 1));
        }
        return image && cursor == b.length;
    }

    private boolean mp4(InputStream source) throws IOException {
        var in = new DataInputStream(source);
        boolean ftyp = false, moov = false, mdat = false;
        int boxes = 0;
        long total = 0;
        while (true) {
            byte[] h = in.readNBytes(8);
            if (h.length == 0) return ftyp && moov && mdat;
            if (h.length != 8 || ++boxes > 10000) return false;
            long size = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(h, 0, 4).getInt());
            String type = ascii(h, 4, 4);
            long header = 8;
            if (size == 1) {
                size = in.readLong();
                header = 16;
            }
            if (size < header || size > 2147483648L || total + size > 2147483648L) return false;
            long remaining = size - header;
            if (type.equals("ftyp")) {
                byte[] brand = in.readNBytes(4);
                if (remaining < 8
                        || brand.length != 4
                        || !Set.of("isom", "iso2", "mp41", "mp42", "avc1")
                                .contains(ascii(brand, 0, 4))) return false;
                remaining -= 4;
                ftyp = true;
            }
            if (type.equals("moov")) moov = remaining >= 8;
            if (type.equals("mdat")) mdat = remaining > 0;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped == 0) {
                    if (in.read() == -1) return false;
                    skipped = 1;
                }
                remaining -= skipped;
            }
            total += size;
        }
    }

    private String ascii(byte[] b, int start, int count) {
        return new String(b, start, count, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
