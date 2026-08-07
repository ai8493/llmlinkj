package com.ai8493.llmproxy.adapter.openai;

/**
 * 拆分 content 中开头的 {@code <think>...</think>} 块为 reasoning + answer。
 *
 * <p>部分上游(MiniMax 等)把 reasoning 塞进 content 字段的 {@code <think>} 标签里,
 * 而不是单独的 reasoning_content 字段。本工具把开头的 think 块拆为 reasoning,
 * 让 IR 后续按 reasoningContent 处理。
 *
 * <p>非流式:{@link #splitLeadingThinkBlock(String)} 一次性拆分。
 * 流式:{@link State} 跨 chunk 维护状态机,处理标签跨 chunk 的情况。
 */
final class InlineThinkSplitter {

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    private InlineThinkSplitter() {}

    // 非流式:拆分开头的 <think>...</think> 块。
    // 返回 [reasoning, answer];若开头不是 <think>,返回 null。
    // reasoning 为 trim 后的内容;answer 剥离前导空白。
    static String[] splitLeadingThinkBlock(String text) {
        if (text == null) return null;
        int leadingWs = countLeadingWhitespace(text);
        String afterWs = text.substring(leadingWs);
        if (!afterWs.startsWith(THINK_OPEN)) return null;

        int bodyStart = leadingWs + THINK_OPEN.length();
        int closeRel = text.indexOf(THINK_CLOSE, bodyStart);
        if (closeRel < 0) return null;
        int closeStart = closeRel;
        int answerStart = closeStart + THINK_CLOSE.length();

        String reasoning = text.substring(bodyStart, closeStart).trim();
        String answer = stripLeadingWhitespace(text.substring(answerStart));
        return new String[]{reasoning, answer};
    }

    // 流式状态机:跨 chunk 维护 DETECTING / REASONING / TEXT 三态
    static final class State {
        private enum Mode { DETECTING, REASONING, TEXT }

        private Mode mode = Mode.DETECTING;
        private final StringBuilder buffer = new StringBuilder();

        // 喂入新 content delta,返回 [reasoningDelta, contentDelta]。
        // 任意一端为空字符串时表示无输出;null 表示整段无输出。
        // 调用方应把非空 reasoningDelta 加到 delta.reasoningContent,contentDelta 加到 delta.content。
        String[] feed(String delta) {
            if (delta == null || delta.isEmpty()) return null;
            return switch (mode) {
                case TEXT -> new String[]{"", delta};
                case DETECTING -> {
                    buffer.append(delta);
                    yield decideDetecting();
                }
                case REASONING -> {
                    buffer.append(delta);
                    yield drainReasoning();
                }
            };
        }

        // 流结束时 flush 残留 buffer,返回 [reasoningDelta, contentDelta] 或 null
        String[] flush() {
            return switch (mode) {
                case TEXT, DETECTING -> {
                    String text = buffer.toString();
                    buffer.setLength(0);
                    mode = Mode.TEXT;
                    yield text.isEmpty() ? null : new String[]{"", text};
                }
                case REASONING -> {
                    String buffered = buffer.toString();
                    buffer.setLength(0);
                    mode = Mode.TEXT;
                    String[] split = splitLeadingThinkBlock(buffered);
                    if (split != null) {
                        String r = split[0];
                        String a = split[1];
                        StringBuilder sb = new StringBuilder();
                        if (!r.isEmpty()) sb.append(r);
                        if (!a.isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(a);
                        }
                        yield sb.length() == 0 ? null : new String[]{sb.toString(), ""};
                    }
                    // 没有闭合 </think>,把 buffer 中 <think> 之后的部分作为 reasoning 输出
                    String reasoning = stripLeadingThinkOpen(buffered);
                    yield reasoning == null || reasoning.isEmpty() ? null : new String[]{reasoning, ""};
                }
            };
        }

        private String[] decideDetecting() {
            String trimmed = buffer.toString().trim();
            if (trimmed.isEmpty()) {
                return null; // NeedMore
            }
            if (trimmed.startsWith(THINK_OPEN)) {
                mode = Mode.REASONING;
                return drainReasoning();
            }
            if (THINK_OPEN.startsWith(trimmed)) {
                return null; // NeedMore(可能是 <think> 的前缀)
            }
            // 判定为 Text,buffer 全部作为 content 输出
            mode = Mode.TEXT;
            String text = buffer.toString();
            buffer.setLength(0);
            return new String[]{"", text};
        }

        private String[] drainReasoning() {
            String[] split = splitLeadingThinkBlock(buffer.toString());
            if (split == null) {
                return null; // 还没收到 </think>,继续 buffer
            }
            mode = Mode.TEXT;
            buffer.setLength(0);
            String r = split[0];
            String a = split[1];
            // reasoning 立即输出,content 后续由 TEXT 状态输出
            // 但 split 给的 answer 也在这里,直接一起返回
            return new String[]{r, a};
        }

        private static String stripLeadingThinkOpen(String text) {
            int leadingWs = countLeadingWhitespace(text);
            String afterWs = text.substring(leadingWs);
            if (!afterWs.startsWith(THINK_OPEN)) return text;
            return afterWs.substring(THINK_OPEN.length()).trim();
        }
    }

    private static int countLeadingWhitespace(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            i++;
        }
        return i;
    }

    private static String stripLeadingWhitespace(String s) {
        int i = countLeadingWhitespace(s);
        return s.substring(i);
    }
}
