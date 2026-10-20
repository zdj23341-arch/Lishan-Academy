package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

class ArticleMetadataIntegrationTest extends ContentHttpSupport {
    Map<String, Object> body(long version) {
        return new LinkedHashMap<>(Map.of("version", version, "title", "真实教学稿",
                "status", "PUBLISHED", "contentBlocks", List.of()));
    }

    @Test
    void metadataRoundTripsWhilePublishedTimeRemainsIndependentOfEditTime() throws Exception {
        String a = admin("CONTENT_EDITOR");
        var request = body(0);
        request.put("authorName", "  教研组  ");
        request.put("categoryName", "  古诗文  ");
        var created = call("POST", "/admin/articles", a, request, 200);
        assertThat(created.path("authorName").asText()).isEqualTo("教研组");
        assertThat(created.path("categoryName").asText()).isEqualTo("古诗文");
        Instant firstUpdated = Instant.parse(created.path("updatedAt").asText());
        String id = created.path("id").asText();
        request = body(created.path("version").asLong());
        request.put("title", "修改后的真实教学稿");
        // A legacy editor omits new fields; it must not erase newer metadata.
        var edited = call("PATCH", "/admin/articles/" + id, a, request, 200);
        assertThat(edited.path("authorName").asText()).isEqualTo("教研组");
        assertThat(edited.path("categoryName").asText()).isEqualTo("古诗文");
        assertThat(Instant.parse(edited.path("updatedAt").asText())).isAfter(firstUpdated);
        assertThat(edited.path("publishedAt")).isEqualTo(created.path("publishedAt"));
        var detail = call("GET", "/articles/" + id, null, null, 200);
        assertThat(detail.path("authorName")).isEqualTo(edited.path("authorName"));
        assertThat(detail.path("categoryName")).isEqualTo(edited.path("categoryName"));
        assertThat(detail.path("updatedAt")).isEqualTo(edited.path("updatedAt"));
        request.put("authorName", "wrong stale author");
        assertThat(call("PATCH", "/admin/articles/" + id, a, request, 409).path("code").asText())
                .isEqualTo("VERSION_CONFLICT");
        request.put("version", edited.path("version").asLong());
        request.put("authorName", " ");
        request.put("categoryName", "");
        var cleared = call("PATCH", "/admin/articles/" + id, a, request, 200);
        assertThat(cleared.path("authorName").isNull()).isTrue();
        assertThat(cleared.path("categoryName").isNull()).isTrue();
        assertThat(call("GET", "/admin/articles/" + id, a, null, 200)).isEqualTo(cleared);
    }

    @Test
    void optionalMetadataIsNullableAndLengthLimitsAreEnforced() throws Exception {
        String a = admin("CONTENT_EDITOR");
        var request = body(0);
        var old = call("POST", "/admin/articles", a, request, 200);
        assertThat(old.path("authorName").isNull()).isTrue();
        assertThat(old.path("categoryName").isNull()).isTrue();
        request.put("authorName", "作".repeat(101));
        call("POST", "/admin/articles", a, request, 400);
        request.put("authorName", "教研组");
        request.put("categoryName", "类".repeat(81));
        call("POST", "/admin/articles", a, request, 400);
        call("POST", "/admin/articles", buyer("buyer"), body(0), 403);
    }
}
