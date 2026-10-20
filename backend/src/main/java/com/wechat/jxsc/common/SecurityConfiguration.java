package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.auth.BearerSessionFilter;
import com.wechat.jxsc.auth.SessionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http, ObjectMapper json, Environment environment, SessionService sessions)
            throws Exception {
        boolean docsAllowed =
                environment.acceptsProfiles(Profiles.of("dev", "test"))
                        && !environment.acceptsProfiles(Profiles.of("prod"));
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new BearerSessionFilter(sessions, json),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll();
                            auth.requestMatchers(HttpMethod.GET, "/api/v1/health/ready")
                                    .permitAll();
                            auth.requestMatchers(
                                            HttpMethod.HEAD,
                                            "/api/v1/protected-content/{token}",
                                            "/api/v1/assets/{id}/content")
                                    .permitAll();
                            auth.requestMatchers(
                                            HttpMethod.GET,
                                            "/api/v1/config/public",
                                            "/api/v1/logistics/carriers",
                                            "/api/v1/referrals/{code}/image",
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
                                            "/api/v1/assets/{id}/content")
                                    .permitAll();
                            auth.requestMatchers("/api/v1/local-uploads/**").authenticated();
                            auth.requestMatchers(HttpMethod.POST, "/api/v1/admin/auth/login")
                                    .permitAll();
                            auth.requestMatchers(HttpMethod.POST, "/api/v1/referrals/resolve")
                                    .permitAll();
                            auth.requestMatchers(
                                            HttpMethod.POST,
                                            "/api/v1/auth/wechat-login",
                                            "/api/v1/webhooks/wechat/payments",
                                            "/api/v1/webhooks/wechat/refunds",
                                            "/api/v1/webhooks/wechat/transfers")
                                    .permitAll();
                            if (docsAllowed
                                    && "DEV"
                                            .equals(
                                                    environment.getProperty(
                                                            "jxsc.providers.identity"))) {
                                auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/dev-login")
                                        .permitAll();
                            }
                            if (docsAllowed) {
                                auth.requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll();
                            }
                            auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                            auth.requestMatchers("/api/v1/operations/**").authenticated();
                            auth.requestMatchers("/api/v1/**").hasRole("CUSTOMER");
                            auth.anyRequest().denyAll();
                        })
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        error(
                                                                json,
                                                                request,
                                                                response,
                                                                401,
                                                                "UNAUTHORIZED",
                                                                "Authentication required"))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        error(
                                                                json,
                                                                request,
                                                                response,
                                                                403,
                                                                "FORBIDDEN",
                                                                "Access denied")));
        return http.build();
    }

    private static void error(
            ObjectMapper json,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(
                response.getOutputStream(),
                new ApiResponse<>(code, message, null, RequestIdFilter.requestId(request)));
    }
}
