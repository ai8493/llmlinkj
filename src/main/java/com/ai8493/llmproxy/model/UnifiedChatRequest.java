package com.ai8493.llmproxy.model;

import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import java.util.List;

public record UnifiedChatRequest(
    String model,
    List<UnifiedMessage> messages,
    UnifiedGenerationConfig config,
    List<UnifiedTool> tools,
    UnifiedToolChoice toolChoice,
    boolean stream,
    AnthropicExtensions anthropic,
    OpenAiExtensions openai,
    GeminiExtensions gemini
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String model;
        private List<UnifiedMessage> messages;
        private UnifiedGenerationConfig config;
        private List<UnifiedTool> tools;
        private UnifiedToolChoice toolChoice;
        private boolean stream;
        private AnthropicExtensions anthropic;
        private OpenAiExtensions openai;
        private GeminiExtensions gemini;

        public Builder model(String model) { this.model = model; return this; }
        public Builder messages(List<UnifiedMessage> messages) { this.messages = messages; return this; }
        public Builder config(UnifiedGenerationConfig config) { this.config = config; return this; }
        public Builder tools(List<UnifiedTool> tools) { this.tools = tools; return this; }
        public Builder toolChoice(UnifiedToolChoice toolChoice) { this.toolChoice = toolChoice; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        public Builder anthropic(AnthropicExtensions anthropic) { this.anthropic = anthropic; return this; }
        public Builder openai(OpenAiExtensions openai) { this.openai = openai; return this; }
        public Builder gemini(GeminiExtensions gemini) { this.gemini = gemini; return this; }

        public UnifiedChatRequest build() {
            return new UnifiedChatRequest(model, messages, config, tools, toolChoice, stream,
                anthropic, openai, gemini);
        }
    }
}
