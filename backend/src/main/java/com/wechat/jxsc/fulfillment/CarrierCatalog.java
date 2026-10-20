package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.commerce.CommerceSupport;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Whitelist of logistics carrier codes accepted when recording shipments. The default catalog
 * covers carriers supported by the Kuaidi100 poll endpoint; deployments can override it with
 * {@code jxsc.logistics.carriers} (comma separated {@code code} or {@code code:name} entries).
 * Test profiles additionally allow the {@code dev} carrier used by integration suites, mirroring
 * the local-endpoint exemption in {@link Kuaidi100Provider}.
 */
@Component
public class CarrierCatalog {
    public record Carrier(String code, String name) {}

    private static final LinkedHashMap<String, String> DEFAULT = new LinkedHashMap<>();

    static {
        DEFAULT.put("shunfeng", "顺丰速运");
        DEFAULT.put("shunfengkuaiyun", "顺丰快运");
        DEFAULT.put("ems", "EMS");
        DEFAULT.put("youzhengguonei", "邮政快递包裹");
        DEFAULT.put("zhongtong", "中通快递");
        DEFAULT.put("yuantong", "圆通速递");
        DEFAULT.put("shentong", "申通快递");
        DEFAULT.put("yunda", "韵达快递");
        DEFAULT.put("jd", "京东物流");
        DEFAULT.put("debangwuliu", "德邦快递");
        DEFAULT.put("jtexpress", "极兔速递");
        DEFAULT.put("htky", "百世快递");
        DEFAULT.put("baishiwuliu", "百世快运");
        DEFAULT.put("annengwuliu", "安能物流");
        DEFAULT.put("zhongtiewuliu", "中铁快运");
        DEFAULT.put("kuayue", "跨越速运");
        DEFAULT.put("youshuwuliu", "优速快递");
        DEFAULT.put("zhaijisong", "宅急送");
        DEFAULT.put("tiantian", "天天快递");
    }

    private final LinkedHashMap<String, String> carriers = new LinkedHashMap<>();

    public CarrierCatalog(Environment env) {
        String override = env.getProperty("jxsc.logistics.carriers", "").strip();
        if (override.isEmpty()) carriers.putAll(DEFAULT);
        else
            for (String entry : override.split(",")) {
                String item = entry.strip();
                if (item.isEmpty())
                    throw new IllegalStateException(
                            "jxsc.logistics.carriers must be comma separated carrier entries");
                String[] parts = item.split(":", 2);
                String code = parts[0].strip();
                if (!code.matches("[a-z0-9]{2,40}"))
                    throw new IllegalStateException(
                            "Invalid carrier code in jxsc.logistics.carriers: " + code);
                carriers.put(
                        code,
                        parts.length == 2 && !parts[1].strip().isEmpty()
                                ? parts[1].strip()
                                : DEFAULT.getOrDefault(code, code));
            }
        boolean test =
                env.acceptsProfiles(Profiles.of("test")) && !env.acceptsProfiles(Profiles.of("prod"));
        if (test && !carriers.containsKey("DEV")) carriers.put("DEV", "开发联调");
    }

    public List<Carrier> list() {
        return carriers.entrySet().stream()
                .map(e -> new Carrier(e.getKey(), e.getValue()))
                .toList();
    }

    public void require(String code) {
        if (!carriers.containsKey(code))
            throw CommerceSupport.invalid(
                    "Unsupported carrier code; use one of: " + String.join(", ", carriers.keySet()));
    }
}
