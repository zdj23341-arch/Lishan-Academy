package com.wechat.jxsc.common.content;

import java.io.IOException;
import java.io.InputStream;

/** Exact design assets shipped with the current mini-program development build. */
final class DevelopmentDesignAssets {
    private static final String ROOT = "/dev-assets/design/";

    private DevelopmentDesignAssets() {}

    static byte[] bookCover() throws IOException {
        return read("covers/sand.png");
    }

    static byte[] courseCover() throws IOException {
        return read("covers/blue.png");
    }

    static byte[] paperCover() throws IOException {
        return read("covers/paper.png");
    }

    static byte[] banner(String name) throws IOException {
        if (!name.equals("books") && !name.equals("writing") && !name.equals("reading"))
            throw new IllegalArgumentException("Unknown development banner: " + name);
        return read("banners/" + name + ".png");
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream source = DevelopmentDesignAssets.class.getResourceAsStream(ROOT + path)) {
            if (source == null) throw new IOException("Missing development design asset: " + path);
            return source.readAllBytes();
        }
    }
}
