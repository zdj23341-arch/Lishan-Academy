package com.wechat.jxsc.payment.wechat;

import static com.wechat.jxsc.payment.wechat.WechatProtocol.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.*;
import java.security.spec.*;
import java.time.Clock;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Trust is deployment-pinned. Keys are never learned from an unsigned network response. */
public final class WechatCrypto {
    private final String merchant, serial;
    private final PrivateKey privateKey;
    private final Map<String, PublicKey> trust;
    private final byte[] apiKey;
    private final Clock clock;

    public WechatCrypto(
            String merchant,
            String serial,
            PrivateKey key,
            Map<String, PublicKey> trust,
            byte[] apiKey,
            Clock clock) {
        this.merchant = merchant;
        this.serial = serial;
        this.privateKey = key;
        this.trust = Map.copyOf(trust);
        this.apiKey = apiKey.clone();
        this.clock = clock;
        if (apiKey.length != 32 || trust.isEmpty())
            throw new IllegalArgumentException("Invalid WeChat key configuration");
    }

    public static PrivateKey privateKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pemBytes(pem)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid merchant private key");
        }
    }

    public static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pemBytes(pem)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid pinned WeChat public key");
        }
    }

    private static byte[] pemBytes(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[^-]+-----", ""));
    }

    public String sign(String value) {
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(bytes(value));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw unavailable("WECHAT_SIGNING_UNAVAILABLE");
        }
    }

    public String authorization(String method, String path, String body) {
        String time = "" + clock.instant().getEpochSecond(),
                nonce = UUID.randomUUID().toString().replace("-", "");
        String signature =
                sign(method + "\n" + path + "\n" + time + "\n" + nonce + "\n" + body + "\n");
        return "WECHATPAY2-SHA256-RSA2048 mchid=\""
                + merchant
                + "\",nonce_str=\""
                + nonce
                + "\",signature=\""
                + signature
                + "\",timestamp=\""
                + time
                + "\",serial_no=\""
                + serial
                + "\"";
    }

    public void verify(byte[] body, Map<String, String> headers) {
        try {
            var h = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            h.putAll(headers);
            String time = Objects.requireNonNull(h.get("Wechatpay-Timestamp")),
                    nonce = Objects.requireNonNull(h.get("Wechatpay-Nonce"));
            long timestamp = Long.parseLong(time), now = clock.instant().getEpochSecond();
            if (timestamp < now - 300
                    || timestamp > now + 300
                    || nonce.isBlank()
                    || nonce.length() > 128) throw new IllegalArgumentException();
            PublicKey key = trust.get(h.get("Wechatpay-Serial"));
            if (key == null) throw new IllegalArgumentException();
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(bytes(time + "\n" + nonce + "\n"));
            signature.update(body);
            signature.update((byte) '\n');
            if (!signature.verify(Base64.getDecoder().decode(h.get("Wechatpay-Signature"))))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw failure("WECHAT_INVALID_SIGNATURE");
        }
    }

    public byte[] decrypt(JsonNode resource) {
        try {
            equal(text(resource, "algorithm"), "AEAD_AES_256_GCM");
            byte[] nonce = bytes(text(resource, "nonce"));
            if (nonce.length != 12) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(apiKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            if (resource.has("associated_data"))
                cipher.updateAAD(bytes(resource.path("associated_data").asText()));
            return cipher.doFinal(Base64.getDecoder().decode(text(resource, "ciphertext")));
        } catch (Exception e) {
            throw failure("WECHAT_INVALID_RESOURCE");
        }
    }

    public String encryptName(String name, String keyId) {
        try {
            PublicKey key = trust.get(keyId);
            if (key == null) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    new OAEPParameterSpec(
                            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
            return Base64.getEncoder().encodeToString(cipher.doFinal(bytes(name)));
        } catch (Exception e) {
            throw unavailable("WECHAT_RECIPIENT_ENCRYPTION_UNAVAILABLE");
        }
    }

    public boolean trusts(String keyId) {
        return keyId != null && trust.containsKey(keyId);
    }
}
