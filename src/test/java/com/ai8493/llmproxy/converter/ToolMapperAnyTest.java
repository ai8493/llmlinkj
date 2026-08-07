package com.ai8493.llmproxy.converter;

import com.ai8493.llmproxy.model.UnifiedToolChoice;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.ToolConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolMapperAnyTest {

    private final ToolMapper mapper = new ToolMapper();

    @Test
    void shouldMapAnyToGeminiAnyMode() {
        ToolConfig config = mapper.mapToolChoice(UnifiedToolChoice.Any.builder().build());
        assertThat(config).isNotNull();
        FunctionCallingConfig fc = config.functionCallingConfig().orElse(null);
        assertThat(fc).isNotNull();
        // FunctionCallingConfigMode 是包装类,Known 是其内部枚举;通过 knownEnum() 取枚举值断言
        assertThat(fc.mode().map(FunctionCallingConfigMode::knownEnum))
            .hasValue(FunctionCallingConfigMode.Known.ANY);
        // Any 不带 allowedFunctionNames(强制调任意工具)
        assertThat(fc.allowedFunctionNames()).isEmpty();
    }
}
