package com.wechat.jxsc.common.content;

import com.wechat.jxsc.common.ApiResponse;
import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.article.ArticleService;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class PublicConfigurationController {
    private final Environment environment;
    private final ArticleService articles;

    public PublicConfigurationController(Environment environment, ArticleService articles) {
        this.environment = environment;
        this.articles = articles;
    }

    public record AgreementLink(String title, String version, String articleId) {}

    public record PublicConfiguration(
            String storeName,
            String customerService,
            Map<String, String> agreementVersions,
            List<String> allowedFileFormats,
            Map<String, AgreementLink> agreementLinks) {}

    @GetMapping("/api/v1/config/public")
    public ApiResponse<PublicConfiguration> get() {
        Map<String, String> versions = new LinkedHashMap<>(Map.of("privacy", "1", "terms", "1"));
        Map<String, AgreementLink> links = new LinkedHashMap<>();
        for (String kind : List.of("terms", "privacy")) {
            AgreementLink link = agreement(kind);
            if (link != null) {
                links.put(kind, link);
                versions.put(kind, link.version());
            }
        }
        return ApiResponse.ok(
                new PublicConfiguration(
                        "力山学堂",
                        null,
                        versions,
                        List.of("DOC", "DOCX", "PDF", "JPG", "JPEG", "PNG", "WEBP"),
                        links));
    }

    private AgreementLink agreement(String kind) {
        String prefix = "jxsc.agreements." + kind + ".";
        String id = environment.getProperty(prefix + "article-id", "").strip();
        String version = environment.getProperty(prefix + "version", "").strip();
        if (version.isEmpty() || !id.matches("[1-9][0-9]{0,18}")) return null;
        long articleId;
        try { articleId = Long.parseLong(id); }
        catch (NumberFormatException invalid) { return null; }
        try {
            var article = articles.detail(articleId); // Same published/public boundary used by the reader.
            boolean readable = article.contentBlocks().stream().anyMatch(block ->
                    (block.type() == BodyBlocks.Type.TEXT && block.text() != null && !block.text().isBlank())
                    || (block.type() == BodyBlocks.Type.IMAGE && block.url() != null && !block.url().isBlank()));
            return readable ? new AgreementLink(article.title(), version, article.id()) : null;
        } catch (ApiException missing) {
            if (missing.getStatus() == HttpStatus.NOT_FOUND) return null;
            throw missing; // Infrastructure/service failures must remain retryable errors.
        }
    }
}
