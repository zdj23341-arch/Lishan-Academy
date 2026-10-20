package com.wechat.jxsc.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.Set;

public final class AuthDtos {
    private AuthDtos() {}

    public record FixtureLogin(@NotBlank @Size(max = 64) String fixture) {}

    public record AdminLogin(
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description =
                                    "Case-sensitive; trailing ASCII spaces (U+0020) are removed for"
                                        + " account lookup and rate limiting. The 100-character"
                                        + " limit applies before normalization.")
                    String loginName,
            @NotBlank @Size(max = 72) String password) {
        public String canonicalLoginName() {
            if (loginName == null) return null;
            int end = loginName.length();
            // Match MySQL utf8mb4_bin PAD SPACE equality without changing case,
            // leading spaces, or other Unicode whitespace.
            while (end > 0 && loginName.charAt(end - 1) == ' ') end--;
            return loginName.substring(0, end);
        }
    }

    public record PasswordChange(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {}

    public record UserView(String id, String nickname, boolean distributor) {}

    public record CustomerLogin(String accessToken, Instant expiresAt, UserView user) {}

    public record AdminView(
            String id,
            String loginName,
            Set<String> roles,
            Set<String> permissions,
            boolean passwordChangeRequired) {}

    public record AdminLoginResult(
            String accessToken,
            Instant expiresAt,
            Set<String> permissions,
            boolean passwordChangeRequired) {}
}
