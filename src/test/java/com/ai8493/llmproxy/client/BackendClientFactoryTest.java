package com.ai8493.llmproxy.client;

import com.ai8493.llmproxy.config.BackendConfig;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackendClientFactoryTest {

    @Test
    void shouldCreateOpenAiClientWithCustomBaseUrl() {
        var config = new BackendConfig(
            "openai", "sk-test-key", "https://api.deepseek.com",
            "deepseek-chat", null,
            Duration.ofSeconds(10), Duration.ofSeconds(120), Duration.ofSeconds(60),
            null, null
        );

        OpenAIClient client = BackendClientFactory.createOpenAiClient(config);

        assertThat(client).isNotNull();
        // 不实际发请求，仅验证构造函数不抛异常
        client.close();
    }
}
