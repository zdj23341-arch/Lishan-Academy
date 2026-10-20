package com.wechat.jxsc.common.storage;

import java.io.*;

public interface FormatScanner {
    record ScanResult(boolean accepted, String status) {}

    default ScanResult scan(String extension, InputStream input) throws IOException {
        boolean accepted = accepts(extension, input);
        return new ScanResult(accepted, accepted ? status() : "FORMAT_REJECTED");
    }

    /**
     * A production implementation must run the required malware and format checks. False means
     * rejected; dependency failure must throw, never return clean.
     */
    boolean accepts(String extension, InputStream input) throws IOException;

    String status();
}
