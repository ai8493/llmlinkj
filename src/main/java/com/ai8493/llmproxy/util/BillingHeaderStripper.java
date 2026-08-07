package com.ai8493.llmproxy.util;

/**
 * 剥离 Claude Code 客户端在 system/instructions 开头动态注入的 x-anthropic-billing-header 行。
 *
 * 旋转的 cch= 值会改变每次请求的 prompt 前缀,导致后端 prefix cache 失效。
 * 仅剥离开头的 billing header 行,后续出现的保留(避免删除用户内容)。
 */
public final class BillingHeaderStripper {

    private static final String PREFIX = "x-anthropic-billing-header:";

    private BillingHeaderStripper() {}

    public static String strip(String text) {
        if (text == null) return null;
        if (text.isEmpty()) return text;

        // 大小写不敏感比较前缀
        if (text.length() < PREFIX.length()
            || !text.substring(0, PREFIX.length()).equalsIgnoreCase(PREFIX)) {
            return text;
        }

        // 找到第一个换行符(\n 或 \r)
        int lineEnd = -1;
        byte[] bytes = text.getBytes();
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n' || bytes[i] == '\r') {
                lineEnd = i;
                break;
            }
        }
        if (lineEnd < 0) {
            // 整个 text 就是一行 billing header,无换行 -> 空字符串
            return "";
        }

        int restStart = lineEnd + 1;
        if (bytes[lineEnd] == '\r' && restStart < bytes.length && bytes[restStart] == '\n') {
            restStart++;
        }

        String rest = text.substring(restStart);
        // 剥离 rest 开头的一个换行符(\r\n / \n / \r)
        if (rest.startsWith("\r\n")) return rest.substring(2);
        if (rest.startsWith("\n") || rest.startsWith("\r")) return rest.substring(1);
        return rest;
    }
}
