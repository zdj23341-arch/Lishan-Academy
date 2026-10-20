package com.wechat.jxsc.common.storage;

import java.io.*;
import java.time.Instant;

/**
 * Providers must seal uploads into an immutable object identity before READY. A production adapter
 * must pin object versions or copy into a write-protected final key. Signing an upload URL alone is
 * not evidence that an object is immutable.
 */
public interface StorageProvider {
    record SealedObject(String key, String version, long bytes) {}

    record SignedUrl(String url, Instant expiresAt) {}

    String uploadUrl(long assetId);

    default boolean directUpload() {
        return false;
    }

    default String uploadUrl(long id, String key, String mime, long bytes, Instant expiry) {
        return uploadUrl(id);
    }

    void writeOnce(String key, InputStream source, long expectedBytes) throws IOException;

    SealedObject seal(String key) throws IOException;

    InputStream open(SealedObject object) throws IOException;

    default InputStream open(SealedObject object, long start, long end) throws IOException {
        var stream = open(object);
        try {
            stream.skipNBytes(start);
            return BoundedStreams.deadline(
                    stream, end - start + 1, java.time.Duration.ofMinutes(5));
        } catch (IOException e) {
            stream.close();
            throw e;
        }
    }

    default SignedUrl sign(SealedObject object, Instant expiresAt) {
        throw new com.wechat.jxsc.common.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "EXTERNAL_SERVICE_UNAVAILABLE",
                "Production signed storage is not configured");
    }
}
