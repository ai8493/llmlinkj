package com.ai8493.llmproxy.util;

public class SensitiveDataMasker {
    private SensitiveDataMasker() {}

    public static String maskApiKey(String key) {
        if (key == null || key.length() <= 4) return "***";
        return "***" + key.substring(key.length() - 4);
    }
}
