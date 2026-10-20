package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.*;

/** All content and configuration here belong to the disposable test application/database. */
class PublicAgreementIntegrationTest extends ContentHttpSupport {
    @Autowired ConfigurableEnvironment environment;

    JsonNode article(String token, String title, String status, List<?> blocks) throws Exception {
        return call("POST", "/admin/articles", token,
                Map.of("version", 0, "title", title, "status", status, "contentBlocks", blocks), 200);
    }

    void configure(Map<String, Object> values, CheckedAction action) throws Exception {
        String name = "agreement-test-" + UUID.randomUUID();
        environment.getPropertySources().addFirst(new MapPropertySource(name, values));
        try { action.run(); } finally { environment.getPropertySources().remove(name); }
    }

    interface CheckedAction { void run() throws Exception; }

    @Test
    void anonymouslyReadsPublishedNonemptyAgreementsAndPreservesLegacyConfiguration() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var terms = article(token, "测试用户协议（仅隔离环境）", "PUBLISHED",
                List.of(Map.of("type", "TEXT", "text", "隔离测试正文，不可用于正式协议。")));
        var privacy = article(token, "测试隐私政策（仅隔离环境）", "PUBLISHED",
                List.of(Map.of("type", "TEXT", "text", "隐私政策隔离样本。")));
        configure(Map.of("jxsc.agreements.terms.article-id", terms.path("id").asText(),
                "jxsc.agreements.terms.version", "2026-10-v2",
                "jxsc.agreements.privacy.article-id", privacy.path("id").asText(),
                "jxsc.agreements.privacy.version", "2026-10-v1"), () -> {
            var config = call("GET", "/config/public", null, null, 200);
            assertThat(config.path("storeName").asText()).isEqualTo("力山学堂");
            assertThat(config.path("allowedFileFormats")).hasSize(7);
            var links = config.path("agreementLinks");
            assertThat(links.size()).isEqualTo(2);
            assertThat(links.path("terms").path("articleId")).isEqualTo(terms.path("id"));
            assertThat(links.path("terms").path("version").asText()).isEqualTo("2026-10-v2");
            assertThat(links.path("terms").path("title")).isEqualTo(terms.path("title"));
            assertThat(config.path("agreementVersions").path("privacy").asText()).isEqualTo("2026-10-v1");
            for (var link : links) {
                var detail = call("GET", "/articles/" + link.path("articleId").asText(), null, null, 200);
                assertThat(detail.path("contentBlocks")).isNotEmpty();
            }
            jdbc.update("update article set status='UNPUBLISHED' where id=?", terms.path("id").asLong());
            assertThat(call("GET", "/config/public", null, null, 200).path("agreementLinks").has("terms")).isFalse();
        });
    }

    @Test
    void doesNotOfferDraftMissingEmptyOrUnversionedContentAsAUsableAgreement() throws Exception {
        String token = admin("CONTENT_EDITOR");
        var draft = article(token, "草稿协议", "DRAFT", List.of(Map.of("type", "TEXT", "text", "草稿")));
        var empty = article(token, "空协议", "PUBLISHED", List.of());
        var published = article(token, "没有版本的协议", "PUBLISHED", List.of(Map.of("type", "TEXT", "text", "测试")));
        for (String id : List.of(draft.path("id").asText(), empty.path("id").asText(), "9223372036854775807", "invalid", "0", "")) {
            configure(Map.of("jxsc.agreements.terms.article-id", id, "jxsc.agreements.terms.version", "1",
                    "jxsc.agreements.privacy.article-id", "", "jxsc.agreements.privacy.version", ""), () -> {
                var config = call("GET", "/config/public", null, null, 200);
                assertThat(config.path("agreementLinks")).isEmpty();
                assertThat(config.path("agreementVersions").path("terms").asText()).isEqualTo("1");
            });
        }
        configure(Map.of("jxsc.agreements.terms.article-id", published.path("id").asText(),
                "jxsc.agreements.terms.version", " ", "jxsc.agreements.privacy.article-id", ""), () ->
                assertThat(call("GET", "/config/public", null, null, 200).path("agreementLinks")).isEmpty());
    }
}
