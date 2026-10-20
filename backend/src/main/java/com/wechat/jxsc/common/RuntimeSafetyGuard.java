package com.wechat.jxsc.common;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class RuntimeSafetyGuard {
    @Bean
    @org.springframework.context.annotation.Profile("prod")
    org.springframework.boot.ApplicationRunner validateProductionCapabilities(
            com.wechat.jxsc.distribution.TransferProvider transfers, Environment environment) {
        return args -> {
            boolean recovery =
                    environment.getProperty("jxsc.production.recovery-only", Boolean.class, false);
            if (!transfers.recoveryReady() || !recovery && !transfers.ready())
                throw new IllegalStateException(
                        "Production transfer capability configuration invalid; check trusted keys,"
                                + " scene reports, callback and limits");
        };
    }

    @Bean
    static BeanFactoryPostProcessor validateRuntime(Environment environment) {
        return beanFactory -> {
            boolean production = environment.acceptsProfiles(Profiles.of("prod"));
            boolean development = environment.acceptsProfiles(Profiles.of("dev", "test"));
            if (Boolean.parseBoolean(environment.getProperty("jxsc.dev-seed.enabled", "false"))
                    && (production || !development)) {
                throw new IllegalStateException(
                        "Development content seeds are forbidden in this runtime");
            }
            if (production && development) {
                throw new IllegalStateException(
                        "The prod profile cannot be combined with dev or test");
            }
            for (String provider :
                    new String[] {
                        "identity", "payment", "transfer", "storage", "video", "logistics"
                    }) {
                String configured = environment.getProperty("jxsc.providers." + provider, "REAL");
                validateMode(provider, configured, production, development);
                // Also reject an unsafe explicit env override even if another property source masks
                // it.
                String override =
                        environment.getProperty(
                                provider.toUpperCase(Locale.ROOT) + "_PROVIDER_MODE");
                if (override != null) {
                    validateMode(provider, override, production, development);
                }
            }
            if (production) {
                var errors = ProductionPreflight.errors(environment);
                if (!errors.isEmpty())
                    throw new IllegalStateException(
                            "Production configuration required or invalid: "
                                    + String.join(", ", errors));
            }
        };
    }

    private static void validateMode(
            String provider, String mode, boolean production, boolean development) {
        if (!"REAL".equals(mode) && !"DEV".equals(mode)) {
            throw new IllegalStateException("Unknown provider mode for " + provider);
        }
        if ("DEV".equals(mode) && (production || !development)) {
            throw new IllegalStateException(
                    "DEV provider is forbidden in this runtime: " + provider);
        }
    }
}
