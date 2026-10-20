package com.wechat.jxsc.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI apiDescription() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Teaching commerce backend")
                                .version("stage-5b")
                                .description(
                                        "Course and membership grants, calendar renewal/refund"
                                            + " replay, learning and protected content. Orders,"
                                            + " inventory, fulfillment, aftersales and durable DEV"
                                            + " payments. Published content, typed catalog,"
                                            + " immutable home snapshots, protected uploads; opaque"
                                            + " bearer sessions, actor permissions, append-only"
                                            + " audit and operation lookup. Development login is"
                                            + " unavailable in production."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("opaque")));
    }

    @Bean
    OpenApiCustomizer learningResumeContract() {
        return api -> {
            var nullable = new io.swagger.v3.oas.models.media.ComposedSchema();
            var reference = new Schema<>();
            reference.set$ref("#/components/schemas/LearningResume");
            var none = new Schema<>();
            none.setTypes(Set.of("null"));
            nullable.setAnyOf(List.of(reference, none));
            nullable.setDescription("Latest enabled chapter progress for the authenticated user; null when no saved record");
            api.getComponents().getSchemas().get("LearningCourseView").getProperties().put("resume", nullable);
        };
    }

    @Bean
    OpenApiCustomizer membershipPlanOfferContract() {
        return api -> {
            // OpenAPI 3.1 annotation allowableValues are emitted as strings; this domain is
            // numeric.
            Schema<Integer> duration = new Schema<>();
            duration.setTypes(Set.of("integer"));
            duration.setFormat("int32");
            duration.setEnum(List.of(6, 12));
            api.getComponents()
                    .getSchemas()
                    .get("MembershipPlanOffer")
                    .getProperties()
                    .put("durationMonths", duration);
        };
    }

    @Bean
    OpenApiCustomizer authenticationContract() {
        Set<String> publicPaths =
                Set.of(
                        "/api/v1/health",
                        "/api/v1/health/ready",
                        "/api/v1/auth/dev-login",
                        "/api/v1/auth/wechat-login",
                        "/api/v1/webhooks/wechat/payments",
                        "/api/v1/webhooks/wechat/refunds",
                        "/api/v1/webhooks/wechat/transfers",
                        "/api/v1/referrals/resolve",
                        "/api/v1/referrals/{code}/image",
                        "/api/v1/admin/auth/login",
                        "/api/v1/config/public",
                        "/api/v1/home",
                        "/api/v1/categories",
                        "/api/v1/products",
                        "/api/v1/products/{id}",
                        "/api/v1/membership/plans",
                        "/api/v1/papers",
                        "/api/v1/papers/{id}",
                        "/api/v1/articles/{id}",
                        "/api/v1/courses/{id}/chapters",
                        "/api/v1/courses/{id}/chapters/{chapterId}/play-url",
                        "/api/v1/protected-content/{token}",
                        "/api/v1/assets/{id}/content");
        return api ->
                api.getPaths()
                        .forEach(
                                (path, item) ->
                                        item.readOperations()
                                                .forEach(
                                                        operation -> {
                                                            operation.setSecurity(
                                                                    publicPaths.contains(path)
                                                                            ? List.of()
                                                                            : List.of(
                                                                                    new SecurityRequirement()
                                                                                            .addList(
                                                                                                    "bearerAuth")));
                                                            Map.of(
                                                                            "400",
                                                                            "VALIDATION_ERROR",
                                                                            "401",
                                                                            "UNAUTHORIZED",
                                                                            "403",
                                                                            "FORBIDDEN",
                                                                            "404",
                                                                            "NOT_FOUND",
                                                                            "429",
                                                                            "RATE_LIMITED")
                                                                    .forEach(
                                                                            (status, code) ->
                                                                                    operation
                                                                                            .getResponses()
                                                                                            .addApiResponse(
                                                                                                    status,
                                                                                                    new ApiResponse()
                                                                                                            .description(
                                                                                                                    code)
                                                                                                            .content(
                                                                                                                    new Content()
                                                                                                                            .addMediaType(
                                                                                                                                    "application/json",
                                                                                                                                    new MediaType()
                                                                                                                                            .schema(
                                                                                                                                                    new Schema<>()
                                                                                                                                                            .$ref(
                                                                                                                                                                    "#/components/schemas/ApiResponseVoid"))))));
                                                        }));
    }
}
