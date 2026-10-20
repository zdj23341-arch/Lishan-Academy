package com.wechat.jxsc.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
public abstract class MySqlIntegrationTest {
    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        MySqlDatabase.configure(registry);
    }
}
