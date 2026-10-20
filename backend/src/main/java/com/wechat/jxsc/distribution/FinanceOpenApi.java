package com.wechat.jxsc.distribution;

import io.swagger.v3.oas.models.media.Schema;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

import java.util.*;

@Configuration(proxyBeanMethods = false)
public class FinanceOpenApi {
    @Bean
    OpenApiCustomizer financeContract() {
        return api -> {
            var w = api.getComponents().getSchemas().get("WithdrawalView");
            Schema<Object> confirmation = new Schema<>();
            Schema<Object> nil = new Schema<>();
            nil.setTypes(Set.of("null"));
            confirmation.setAnyOf(
                    List.of(new Schema<>().$ref("#/components/schemas/TransferConfirmation"), nil));
            w.getProperties().put("confirmation", confirmation);
            api.getPaths().get("/api/v1/referrals/resolve").getPost().setSecurity(List.of());
        };
    }
}
