package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.DeserializationFeature;

import jakarta.servlet.http.*;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class RequestValidationConfiguration implements WebMvcConfigurer {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictRequests() {
        return builder ->
                builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .featuresToDisable(
                                DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                                com.fasterxml.jackson.databind.MapperFeature
                                        .ALLOW_COERCION_OF_SCALARS);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                if (request.getParameterMap().values().stream()
                                        .anyMatch(values -> values.length > 1))
                                    throw new ApiException(
                                            HttpStatus.BAD_REQUEST,
                                            "VALIDATION_ERROR",
                                            "Repeated query fields are not allowed");
                                return true;
                            }
                        })
                .addPathPatterns("/api/v1/**");
    }
}
