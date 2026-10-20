package com.wechat.jxsc.common.content;

/** Access to self-authored seed files for real upload-boundary tests. */
public final class DevelopmentFileFixtures {
    public static byte[] docx() throws Exception {
        return AuthoredDevelopmentFiles.docx();
    }

    public static byte[] pdf() throws Exception {
        return AuthoredDevelopmentFiles.pdf();
    }

    private DevelopmentFileFixtures() {}
}
