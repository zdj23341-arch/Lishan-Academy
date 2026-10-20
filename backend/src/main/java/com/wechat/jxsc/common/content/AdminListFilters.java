package com.wechat.jxsc.common.content;

public final class AdminListFilters {
    private AdminListFilters() {}

    public static String keyword(String value) {
        if (value == null) return "";
        if (value.length() > 200) throw ContentSupport.invalid("Keyword too long");
        return value.strip();
    }

    public static Long searchId(String keyword) {
        if (!keyword.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.parseLong(keyword); }
        catch (NumberFormatException ignored) { return null; }
    }
}
