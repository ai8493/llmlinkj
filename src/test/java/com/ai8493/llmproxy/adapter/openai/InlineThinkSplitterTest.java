package com.ai8493.llmproxy.adapter.openai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InlineThinkSplitterTest {

    // ===== 非流式 splitLeadingThinkBlock =====

    @Test
    void shouldSplitLeadingThinkBlock() {
        String[] r = InlineThinkSplitter.splitLeadingThinkBlock("<think>reasoning</think>actual text");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("reasoning");
        assertThat(r[1]).isEqualTo("actual text");
    }

    @Test
    void shouldSplitLeadingThinkBlockWithLeadingWhitespace() {
        String[] r = InlineThinkSplitter.splitLeadingThinkBlock("  \n<think>reasoning</think>actual");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("reasoning");
        assertThat(r[1]).isEqualTo("actual");
    }

    @Test
    void shouldSplitLeadingThinkBlockWithEmptyAnswer() {
        String[] r = InlineThinkSplitter.splitLeadingThinkBlock("<think>only reasoning</think>");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("only reasoning");
        assertThat(r[1]).isEqualTo("");
    }

    @Test
    void shouldReturnNullWhenNoThinkTag() {
        assertThat(InlineThinkSplitter.splitLeadingThinkBlock("plain text")).isNull();
    }

    @Test
    void shouldReturnNullWhenThinkNotAtLeading() {
        assertThat(InlineThinkSplitter.splitLeadingThinkBlock("text <think>r</think> after")).isNull();
    }

    @Test
    void shouldReturnNullWhenUnclosedThink() {
        assertThat(InlineThinkSplitter.splitLeadingThinkBlock("<think>unclosed")).isNull();
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertThat(InlineThinkSplitter.splitLeadingThinkBlock(null)).isNull();
    }

    @Test
    void shouldTrimReasoningContent() {
        String[] r = InlineThinkSplitter.splitLeadingThinkBlock("<think>  reasoning with spaces  </think>answer");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("reasoning with spaces");
        assertThat(r[1]).isEqualTo("answer");
    }

    // ===== 流式 State =====

    @Test
    void shouldStreamSingleChunkWithThinkBlock() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        String[] r = state.feed("<think>reasoning</think>actual");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("reasoning");
        assertThat(r[1]).isEqualTo("actual");
    }

    @Test
    void shouldStreamSplitAcrossChunks() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        // <think> 标签跨 chunk
        assertThat(state.feed("<thi")).isNull();
        // 加上 nk> 后,DETECTING 判定为 REASONING,但还没 </think>,继续 buffer
        assertThat(state.feed("nk>reasoning")).isNull();
        String[] r = state.feed("</think>actual");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("reasoning");
        assertThat(r[1]).isEqualTo("actual");
    }

    @Test
    void shouldStreamTextWithoutThink() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        // 第一个 chunk 不以 <think> 开头 -> TEXT 状态
        String[] r = state.feed("plain text");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("");
        assertThat(r[1]).isEqualTo("plain text");
        // 后续 chunk 直接透传
        String[] r2 = state.feed(" more text");
        assertThat(r2).isNotNull();
        assertThat(r2[0]).isEqualTo("");
        assertThat(r2[1]).isEqualTo(" more text");
    }

    @Test
    void shouldStreamMultipleChunksBeforeDecision() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        // 累积 "<" 不够决定
        assertThat(state.feed("<")).isNull();
        // 加上 "t" 还不够
        assertThat(state.feed("t")).isNull();
        // 加上 "hi" 还不够
        assertThat(state.feed("hi")).isNull();
        // 加上 "nk>" 决定为 REASONING,但还没 </think>,返回 null
        assertThat(state.feed("nk>reasoning")).isNull();
        // </think> 闭合,输出 reasoning + answer
        String[] r = state.feed("</think>answer");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("reasoning");
        assertThat(r[1]).isEqualTo("answer");
    }

    @Test
    void shouldStreamFlushUnclosedReasoning() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        state.feed("<think>partial reasoning");
        // 流结束,没有 </think>,flush 时把 reasoning 输出
        String[] r = state.flush();
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("partial reasoning");
        assertThat(r[1]).isEqualTo("");
    }

    @Test
    void shouldStreamFlushEmptyBuffer() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        assertThat(state.flush()).isNull();
    }

    @Test
    void shouldStreamFlushTextBuffer() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        // 累积 "<" 后 flush(没等到决定)
        state.feed("<");
        String[] r = state.flush();
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("");
        assertThat(r[1]).isEqualTo("<");
    }

    @Test
    void shouldStreamTextAfterThinkBlock() {
        InlineThinkSplitter.State state = new InlineThinkSplitter.State();
        state.feed("<think>reasoning</think>");
        // think 块结束后,后续 content 应该直接作为 text 输出
        String[] r = state.feed("actual answer");
        assertThat(r).isNotNull();
        assertThat(r[0]).isEqualTo("");
        assertThat(r[1]).isEqualTo("actual answer");
    }
}
