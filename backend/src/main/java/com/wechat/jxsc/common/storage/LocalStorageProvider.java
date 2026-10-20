package com.wechat.jxsc.common.storage;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.common.content.ContentSupport;

import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.file.*;

/** Explicit dev/test filesystem provider. Original filenames never enter a path. */
public final class LocalStorageProvider implements StorageProvider {
    private final Path root;

    public LocalStorageProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path path(String key) {
        if (!key.matches("[a-f0-9]{32}")) throw ContentSupport.invalid("Invalid object key");
        return root.resolve(key);
    }

    public String uploadUrl(long id) {
        return "/api/v1/local-uploads/" + id;
    }

    public void writeOnce(String key, InputStream source, long expected) throws IOException {
        Files.createDirectories(root);
        Path object = path(key);
        boolean created = false;
        try (var output =
                Files.newOutputStream(
                        object, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            byte[] buffer = new byte[65536];
            long count = 0;
            int n;
            while ((n = source.read(buffer)) != -1) {
                count += n;
                if (count > expected)
                    throw ContentSupport.invalid("Uploaded bytes exceed declared size");
                output.write(buffer, 0, n);
            }
            if (count != expected)
                throw ContentSupport.invalid("Uploaded byte count differs from declared size");
        } catch (FileAlreadyExistsException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "FILE_ALREADY_UPLOADED", "Object already exists");
        } catch (IOException | RuntimeException e) {
            if (created) Files.deleteIfExists(object);
            throw e;
        }
    }

    public SealedObject seal(String key) throws IOException {
        Path object = path(key);
        if (!Files.isRegularFile(object))
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_READY", "Upload bytes first");
        return new SealedObject(key, "immutable-v1", Files.size(object));
    }

    public InputStream open(SealedObject object) throws IOException {
        return Files.newInputStream(path(object.key()));
    }
}
