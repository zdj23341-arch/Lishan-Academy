package com.wechat.jxsc.common.storage;

import com.wechat.jxsc.common.ApiException;

import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    static boolean local(Environment e) {
        return e.acceptsProfiles(Profiles.of("dev", "test"))
                && !e.acceptsProfiles(Profiles.of("prod"))
                && "DEV".equals(e.getProperty("jxsc.providers.storage"));
    }

    static ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EXTERNAL_SERVICE_UNAVAILABLE",
                "Storage and malware scanning require production adapters");
    }

    @Bean
    StorageProvider storageProvider(Environment e) {
        if (local(e))
            return new LocalStorageProvider(
                    Path.of(e.getProperty("jxsc.storage.local-root", ".runtime/uploads")));
        return new CosStorageProvider(e);
    }

    @Bean
    FormatScanner formatScanner(Environment e) {
        if (local(e)) return new DevelopmentFormatScanner();
        return new ProductionFormatScanner(e);
    }
}
