package com.wechat.jxsc.payment.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.*;

import java.net.URI;
import java.nio.file.*;
import java.security.PublicKey;
import java.time.Clock;
import java.util.*;

@Configuration(proxyBeanMethods = false)
public class WechatConfiguration {
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer wechatWebhookContract() {
        return api -> {
            for (String kind : new String[] {"payments", "refunds", "transfers"}) {
                var operation = api.getPaths().get("/api/v1/webhooks/wechat/" + kind).getPost();
                operation.setRequestBody(
                        new io.swagger.v3.oas.models.parameters.RequestBody()
                                .required(true)
                                .content(
                                        new io.swagger.v3.oas.models.media.Content()
                                                .addMediaType(
                                                        "application/json",
                                                        new io.swagger.v3.oas.models.media
                                                                        .MediaType()
                                                                .schema(
                                                                        new io.swagger.v3.oas.models
                                                                                .media
                                                                                .ObjectSchema()))));
                operation.getResponses().remove("200");
                operation
                        .getResponses()
                        .addApiResponse(
                                "204",
                                new io.swagger.v3.oas.models.responses.ApiResponse()
                                        .description(
                                                "Authenticated callback accepted; empty response"));
                for (String header :
                        new String[] {
                            "Wechatpay-Serial",
                            "Wechatpay-Signature",
                            "Wechatpay-Timestamp",
                            "Wechatpay-Nonce"
                        })
                    operation.addParametersItem(
                            new io.swagger.v3.oas.models.parameters.HeaderParameter()
                                    .name(header)
                                    .required(true)
                                    .schema(new io.swagger.v3.oas.models.media.StringSchema()));
            }
        };
    }

    @Bean
    WechatHttp wechatHttp(WechatSettings s, ObjectMapper json, Clock clock, WechatEvidence evidence)
            throws Exception {
        WechatCrypto crypto = null;
        if (s.enabled("pay.enabled")) {
            if (!s.appId().matches("[A-Za-z0-9]{1,32}")
                    || !s.merchantId().matches("[0-9]{1,32}")
                    || !s.get("pay.merchant-serial").matches("[A-Za-z0-9]{1,64}"))
                throw new IllegalStateException("Invalid WeChat merchant configuration");
            Map<String, PublicKey> trust = new HashMap<>();
            var keys = json.readTree(s.get("pay.trusted-public-key-files"));
            keys.properties()
                    .forEach(
                            e -> {
                                try {
                                    trust.put(
                                            e.getKey(),
                                            WechatCrypto.publicKey(
                                                    Files.readString(
                                                            Path.of(e.getValue().asText()))));
                                } catch (Exception error) {
                                    throw new IllegalStateException(
                                            "Cannot load trusted WeChat public keys");
                                }
                            });
            crypto =
                    new WechatCrypto(
                            s.merchantId(),
                            s.get("pay.merchant-serial"),
                            WechatCrypto.privateKey(
                                    Files.readString(Path.of(s.get("pay.private-key-file")))),
                            trust,
                            WechatProtocol.bytes(s.get("pay.api-v3-key")),
                            clock);
            s.notifyUrl("payments");
        }
        return new WechatHttp(
                URI.create("https://api.mch.weixin.qq.com"),
                crypto,
                json,
                e -> {
                    try {
                        String payload = json.writeValueAsString(e);
                        evidence.record(
                                "WECHAT_HTTP",
                                "HTTP",
                                com.wechat.jxsc.auth.TokenCodec.hash(e.path()),
                                UUID.randomUUID().toString(),
                                payload,
                                payload,
                                false);
                    } catch (com.wechat.jxsc.common.ApiException error) {
                        throw error;
                    } catch (Exception error) {
                        throw WechatProtocol.failure("WECHAT_EVIDENCE_UNAVAILABLE");
                    }
                },
                crypto == null ? null : s.responseSerial(crypto));
    }
}
