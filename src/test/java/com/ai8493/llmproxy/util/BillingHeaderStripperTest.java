package com.ai8493.llmproxy.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingHeaderStripperTest {

    @Test
    void shouldStripLeadingBillingHeaderLine() {
        // 标准:billing header 行 + 后续 prompt
        String input = "x-anthropic-billing-header: cc_version=2.1.119.47e; cc_entrypoint=sdk-cli; cch=a7754;\n\nYou are a helpful assistant.";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void shouldNotStripWhenNotStartingWithHeader() {
        // 不以 billing header 开头,原样返回
        String input = "You are a helpful assistant.";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo(input);
    }

    @Test
    void shouldReturnEmptyWhenOnlyBillingHeaderLine() {
        // 整个 text 就是一行 billing header(无换行)-> 空字符串
        String input = "x-anthropic-billing-header: cch=abc123";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo("");
    }

    @Test
    void shouldHandleCrlfLineEnding() {
        // Windows 换行 \r\n
        String input = "x-anthropic-billing-header: cch=abc\r\nYou are helpful.";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo("You are helpful.");
    }

    @Test
    void shouldHandleCrOnlyLineEnding() {
        // 老 Mac 换行 \r
        String input = "x-anthropic-billing-header: cch=abc\rYou are helpful.";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo("You are helpful.");
    }

    @Test
    void shouldPreserveLaterOccurrencesOfHeader() {
        // 后续出现的 billing header 保留(避免删除用户内容)
        String input = "x-anthropic-billing-header: cch=abc\n\nUser says: x-anthropic-billing-header: something";
        assertThat(BillingHeaderStripper.strip(input))
            .isEqualTo("User says: x-anthropic-billing-header: something");
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertThat(BillingHeaderStripper.strip(null)).isNull();
    }

    @Test
    void shouldReturnEmptyForEmptyInput() {
        assertThat(BillingHeaderStripper.strip("")).isEqualTo("");
    }

    @Test
    void shouldHandleHeaderWithMultipleNewlinesAfter() {
        // billing header 后跟多个换行,只剥离一行 billing header + 一个换行
        String input = "x-anthropic-billing-header: cch=abc\n\n\n\nYou are helpful.";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo("\n\nYou are helpful.");
    }

    @Test
    void shouldBeCaseInsensitiveForPrefix() {
        // 前缀大小写不敏感(实测 Claude Code 发小写,但防御性处理)
        String input = "X-Anthropic-Billing-Header: cch=abc\nYou are helpful.";
        assertThat(BillingHeaderStripper.strip(input)).isEqualTo("You are helpful.");
    }
}
