package com.wechat.jxsc.payment;

import com.wechat.jxsc.payment.wechat.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "jxsc.providers.payment",
            havingValue = "REAL",
            matchIfMissing = true)
    PaymentProvider wechatPaymentProvider(WechatHttp http, WechatSettings settings, Clock clock) {
        return new WechatPaymentProvider(http, settings, clock);
    }
}
