package com.wechat.jxsc.common.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DevelopmentDesignAssetsTest {
    @Test
    void loadsTheCurrentMiniProgramDesignAssets() throws Exception {
        assertSha256(
                "f0bb5b45da5a3a7ba6f6d7bfbbf5ace3293f0a656cf1c4d2ca5c1246aa16ce87",
                DevelopmentDesignAssets.bookCover());
        assertSha256(
                "7cc6cd0a37be1feb9af8541bdc3716fe4bbe94dcfa73d4d05f1edd103fe4c681",
                DevelopmentDesignAssets.courseCover());
        assertSha256(
                "0435c0fe61acbdbe16dfe6b03438e4fa8de6f74f40d1601b9e24c4183f19e11b",
                DevelopmentDesignAssets.paperCover());
        assertSha256(
                "e45ca7d06c771be7881c8c1c380fa6f90b8d3277fcb18689e4494fd26ba4832a",
                DevelopmentDesignAssets.banner("books"));
        assertSha256(
                "fd626c2f3d084a2e534575dfd618fa23fbf609938900501ac0e22782aa8f81f9",
                DevelopmentDesignAssets.banner("writing"));
        assertSha256(
                "eab19fac491491712cecaf53e302acb43176f883b242934361751f753b622f6e",
                DevelopmentDesignAssets.banner("reading"));
    }

    private static void assertSha256(String expected, byte[] actual) throws Exception {
        assertEquals(
                expected,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)));
    }
}
