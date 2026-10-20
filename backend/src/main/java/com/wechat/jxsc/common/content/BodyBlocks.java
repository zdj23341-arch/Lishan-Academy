package com.wechat.jxsc.common.content;

import com.wechat.jxsc.common.storage.AssetService;

import jakarta.validation.constraints.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class BodyBlocks {
    public enum Type {
        TEXT,
        IMAGE
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ContentBlockInput")
    public record Input(@NotNull Type type, @Size(max = 10000) String text, String assetId) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ContentBlockView")
    public record View(Type type, String text, String assetId, String url) {}

    private final AssetService assets;

    public BodyBlocks(AssetService assets) {
        this.assets = assets;
    }

    public List<Input> validate(List<Input> blocks) {
        if (blocks == null || blocks.size() > 100)
            throw ContentSupport.invalid("Up to 100 content blocks required");
        for (var b : blocks) {
            if (b == null || b.type() == null) throw ContentSupport.invalid("Invalid block");
            if (b.type() == Type.TEXT) {
                if (b.text() == null
                        || b.text().isBlank()
                        || b.text().length() > 10000
                        || b.assetId() != null)
                    throw ContentSupport.invalid("TEXT requires plain text only");
            } else {
                if (b.text() != null) throw ContentSupport.invalid("IMAGE requires an asset only");
                assets.requirePurpose(ContentSupport.id(b.assetId()), "PUBLIC_IMAGE", true);
            }
        }
        return List.copyOf(blocks);
    }

    public List<View> view(List<Input> blocks) {
        return blocks.stream()
                .map(
                        b ->
                                new View(
                                        b.type(),
                                        b.text(),
                                        b.assetId(),
                                        b.assetId() == null
                                                ? null
                                                : assets.publicUrl(ContentSupport.id(b.assetId()))))
                .toList();
    }
}
