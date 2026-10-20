package com.wechat.jxsc.common.commerce;

import org.springframework.core.env.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

/** AES-GCM ciphertext contains a random nonce; the key is supplied by deployment secret storage. */
@Component
public class SensitiveCipher {
    private final SecretKeySpec key;
    private final Map<String, SecretKeySpec> keyring = new HashMap<>();
    private final String activeKey;
    private final SecureRandom random = new SecureRandom();

    public SensitiveCipher(Environment env) throws Exception {
        String configured =
                env.getProperty(
                        "jxsc.crypto.key-base64", env.getProperty("JXSC_DATA_KEY_BASE64", ""));
        if (configured.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("prod"))
                    || !env.acceptsProfiles(Profiles.of("dev", "test")))
                throw new IllegalStateException(
                        "JXSC_DATA_KEY_BASE64 is required for sensitive data encryption");
            Path file =
                    Path.of(
                                    env.getProperty(
                                            "jxsc.crypto.dev-key-file",
                                            ".runtime/keys/commerce-data.key"))
                            .toAbsolutePath();
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                try {
                    Files.writeString(
                            file,
                            Base64.getEncoder().encodeToString(bytes),
                            StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException ignored) {
                }
            }
            configured = Files.readString(file).trim();
        }
        byte[] bytes = Base64.getDecoder().decode(configured);
        if (bytes.length != 32)
            throw new IllegalStateException("Encryption key must contain 32 bytes");
        key = new SecretKeySpec(bytes, "AES");
        activeKey = env.getProperty("jxsc.crypto.active-key-id", "");
        try {
            var ring =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(env.getProperty("jxsc.crypto.keyring-json", "{}"));
            if (!ring.isObject() || ring.size() > 32) throw new IllegalArgumentException();
            for (var entry : ring.properties()) {
                byte[] decoded = Base64.getDecoder().decode(entry.getValue().asText());
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,40}") || decoded.length != 32)
                    throw new IllegalArgumentException();
                keyring.put(entry.getKey(), new SecretKeySpec(decoded, "AES"));
            }
            if (!activeKey.isBlank() && !keyring.containsKey(activeKey))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid encryption keyring configuration");
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    activeKey.isBlank() ? key : keyring.get(activeKey),
                    new GCMParameterSpec(128, nonce));
            return (activeKey.isBlank() ? "v1:" : "v2:" + activeKey + ":")
                    + Base64.getEncoder().encodeToString(nonce)
                    + ":"
                    + Base64.getEncoder()
                            .encodeToString(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt sensitive data", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            String[] p = ciphertext.split(":", 4);
            boolean version2 = p[0].equals("v2");
            if (!(p[0].equals("v1") && p.length == 3 || version2 && p.length == 4))
                throw new IllegalStateException();
            int offset = version2 ? 1 : 0;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    version2 ? keyring.get(p[1]) : key,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(p[1 + offset])));
            return new String(
                    c.doFinal(Base64.getDecoder().decode(p[2 + offset])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt sensitive data", e);
        }
    }
}
