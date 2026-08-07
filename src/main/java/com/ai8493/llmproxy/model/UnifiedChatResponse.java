package com.ai8493.llmproxy.model;

import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import java.util.List;

public record UnifiedChatResponse(
    String id,
    String model,
    String object,
    long created,
    List<UnifiedChoice> choices,
    UnifiedUsage usage,
    String systemFingerprint,
    AnthropicExtensions anthropic,
    OpenAiExtensions openai,
    GeminiExtensions gemini
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String model;
        private String object;
        private long created;
        private List<UnifiedChoice> choices;
        private UnifiedUsage usage;
        private String systemFingerprint;
        private AnthropicExtensions anthropic;
        private OpenAiExtensions openai;
        private GeminiExtensions gemini;

        public Builder id(String id) { this.id = id; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder object(String object) { this.object = object; return this; }
        public Builder created(long created) { this.created = created; return this; }
        public Builder choices(List<UnifiedChoice> choices) { this.choices = choices; return this; }
        public Builder usage(UnifiedUsage usage) { this.usage = usage; return this; }
        public Builder systemFingerprint(String systemFingerprint) { this.systemFingerprint = systemFingerprint; return this; }
        public Builder anthropic(AnthropicExtensions anthropic) { this.anthropic = anthropic; return this; }
        public Builder openai(OpenAiExtensions openai) { this.openai = openai; return this; }
        public Builder gemini(GeminiExtensions gemini) { this.gemini = gemini; return this; }

        public UnifiedChatResponse build() {
            return new UnifiedChatResponse(id, model, object, created, choices, usage,
                systemFingerprint, anthropic, openai, gemini);
        }
    }
}
