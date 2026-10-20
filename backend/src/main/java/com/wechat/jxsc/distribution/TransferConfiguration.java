package com.wechat.jxsc.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.distribution.wechat.WechatTransferProvider;
import com.wechat.jxsc.payment.wechat.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class TransferConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "jxsc.providers.transfer",
            havingValue = "REAL",
            matchIfMissing = true)
    TransferProvider wechatTransfer(WechatHttp http, WechatSettings settings, ObjectMapper json) {
        return new WechatTransferProvider(http, settings, json);
    }
}
