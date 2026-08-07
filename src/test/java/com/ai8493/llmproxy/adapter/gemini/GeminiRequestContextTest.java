package com.ai8493.llmproxy.adapter.gemini;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiRequestContextTest {

    @Test
    void shouldHoldSessionKey() {
        GeminiRequestContext ctx = new GeminiRequestContext("anon:abc1");
        assertThat(ctx.sessionKey()).isEqualTo("anon:abc1");
    }

    @Test
    void shouldInitializeEmptyToolCallAccs() {
        GeminiRequestContext ctx = new GeminiRequestContext("test");
        assertThat(ctx.toolCallAccs()).isEmpty();
    }

    @Test
    void shouldAccumulateToolCallAccsByIndex() {
        GeminiRequestContext ctx = new GeminiRequestContext("test");
        GeminiRequestContext.ToolCallAcc acc0 = ctx.toolCallAccs()
            .computeIfAbsent(0, k -> new GeminiRequestContext.ToolCallAcc());
        GeminiRequestContext.ToolCallAcc acc1 = ctx.toolCallAccs()
            .computeIfAbsent(1, k -> new GeminiRequestContext.ToolCallAcc());

        assertThat(ctx.toolCallAccs()).hasSize(2);
        assertThat(ctx.toolCallAccs().get(0)).isSameAs(acc0);
        assertThat(ctx.toolCallAccs().get(1)).isSameAs(acc1);
    }

    @Test
    void toolCallAccResetShouldClearAllFields() {
        GeminiRequestContext.ToolCallAcc acc = new GeminiRequestContext.ToolCallAcc();
        acc.id = "call_1";
        acc.fnName = "get_weather";
        acc.argsBuilder.append("{\"location\":\"Beijing\"}");

        acc.reset();

        assertThat(acc.id).isNull();
        assertThat(acc.fnName).isNull();
        assertThat(acc.argsBuilder).isEmpty();
    }
}
