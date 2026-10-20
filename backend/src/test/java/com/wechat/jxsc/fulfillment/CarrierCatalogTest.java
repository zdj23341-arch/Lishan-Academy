package com.wechat.jxsc.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.wechat.jxsc.common.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CarrierCatalogTest {
    private CarrierCatalog catalog(MockEnvironment env) {
        return new CarrierCatalog(env);
    }

    @Test
    void defaultCatalogCoversCommonCarriersWithoutDev() {
        var catalog = catalog(new MockEnvironment());
        var codes = catalog.list().stream().map(CarrierCatalog.Carrier::code).toList();
        assertThat(codes)
                .contains("shunfeng", "shunfengkuaiyun", "ems", "zhongtong", "yuantong", "jd");
        assertThat(codes).doesNotContain("dev");
        assertThat(catalog.list())
                .anySatisfy(c -> {
                    assertThat(c.code()).isEqualTo("shunfeng");
                    assertThat(c.name()).isEqualTo("顺丰速运");
                });
    }

    @Test
    void testProfileAddsDevCarrierButProdDoesNot() {
        var test = catalog(new MockEnvironment().withProperty("spring.profiles.active", "test"));
        assertThat(test.list().stream().map(CarrierCatalog.Carrier::code)).contains("DEV");
        var prod =
                catalog(new MockEnvironment().withProperty("spring.profiles.active", "prod,test"));
        assertThat(prod.list().stream().map(CarrierCatalog.Carrier::code)).doesNotContain("DEV");
    }

    @Test
    void overrideReplacesCatalogAndParsesOptionalNames() {
        var catalog =
                catalog(
                        new MockEnvironment()
                                .withProperty("jxsc.logistics.carriers", "shunfeng, demo:示例物流"));
        var entries = catalog.list();
        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst().code()).isEqualTo("shunfeng");
        assertThat(entries.getFirst().name()).isEqualTo("顺丰速运");
        assertThat(entries.get(1).code()).isEqualTo("demo");
        assertThat(entries.get(1).name()).isEqualTo("示例物流");
    }

    @Test
    void malformedOverrideFailsFast() {
        assertThatThrownBy(() -> catalog(new MockEnvironment().withProperty("jxsc.logistics.carriers", "shunfeng,,ems")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> catalog(new MockEnvironment().withProperty("jxsc.logistics.carriers", "Not A Code")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireAcceptsKnownCodeAndRejectsOthers() {
        var catalog = catalog(new MockEnvironment());
        assertThatCode(() -> catalog.require("yunda")).doesNotThrowAnyException();
        assertThatThrownBy(() -> catalog.require("SF"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("shunfeng");
    }
}
