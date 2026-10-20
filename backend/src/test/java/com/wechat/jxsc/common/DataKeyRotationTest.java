package com.wechat.jxsc.common;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.commerce.SensitiveCipher;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DataKeyRotationTest {
    @Test
    void newWritesUseActiveKeyWhileImmutableOldEvidenceRemainsReadable() throws Exception {
        String oldKey = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        byte[] newer = new byte[32];
        java.util.Arrays.fill(newer, (byte) 7);
        String newKey = java.util.Base64.getEncoder().encodeToString(newer);
        var old =
                new SensitiveCipher(
                        new MockEnvironment().withProperty("jxsc.crypto.key-base64", oldKey));
        String historic = old.encrypt("immutable evidence");
        var env =
                new MockEnvironment()
                        .withProperty("jxsc.crypto.key-base64", oldKey)
                        .withProperty("jxsc.crypto.active-key-id", "oct2026")
                        .withProperty(
                                "jxsc.crypto.keyring-json", "{\"oct2026\":\"" + newKey + "\"}");
        var rotated = new SensitiveCipher(env);
        assertThat(rotated.encrypt("new evidence")).startsWith("v2:oct2026:");
        assertThat(rotated.decrypt(historic)).isEqualTo("immutable evidence");
        assertThat(rotated.decrypt(rotated.encrypt("new evidence"))).isEqualTo("new evidence");
        assertThatThrownBy(() -> old.decrypt(rotated.encrypt("new evidence")))
                .isInstanceOf(IllegalStateException.class);
    }
}
