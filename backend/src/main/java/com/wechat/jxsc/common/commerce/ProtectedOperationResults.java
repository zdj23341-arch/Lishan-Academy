package com.wechat.jxsc.common.commerce;

import com.fasterxml.jackson.databind.*;

import org.springframework.stereotype.Component;

/** Response caching may include addresses. Only actor-authorized operation lookups reveal it. */
@Component
public class ProtectedOperationResults {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.ClosedAccountPrivacy privacy;

    private final SensitiveCipher cipher;
    private final ObjectMapper json;

    public ProtectedOperationResults(SensitiveCipher cipher, ObjectMapper json) {
        this.cipher = cipher;
        this.json = json;
    }

    public JsonNode protect(JsonNode result) {
        return json.createObjectNode().put("_protected_v1", cipher.encrypt(result.toString()));
    }

    public JsonNode reveal(JsonNode stored) {
        if (!stored.has("_protected_v1")) return privacy.sanitize(stored);
        try {
            return privacy.sanitize(
                    json.readTree(cipher.decrypt(stored.path("_protected_v1").asText())));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read protected operation result", e);
        }
    }
}
