package com.ai8493.llmproxy.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonPointer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 按 language 分派的配置字段替换器。无状态，Spring 单例。
// toml 用行级状态机（codex 的 toml 结构简单，无需第三方 toml 库）
// json 用 Jackson JsonPointer 定位后 put 值
// ini 用行级 KEY=VALUE 替换
@Component
public class ConfigFieldUpdater {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    public String update(String content, String language,
                         List<UpdatableField> fields, Map<String, String> renderedVars) {
        return switch (language) {
            case "toml" -> updateToml(content, fields, renderedVars);
            case "json" -> updateJson(content, fields, renderedVars);
            case "ini" -> updateIni(content, fields, renderedVars);
            default -> throw new IllegalStateException("不支持的文件类型: " + language);
        };
    }

    // 行级状态机：维护当前 section，匹配 section.key 全路径后替换等号右边值
    // 注释行（# 开头）、空行、section 头、不匹配的 key 原样保留
    private String updateToml(String content, List<UpdatableField> fields, Map<String, String> renderedVars) {
        StringBuilder out = new StringBuilder();
        String currentSection = "";
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String result = line;

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1);
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx >= 0) {
                    String key = trimmed.substring(0, eqIdx).trim();
                    String fullKey = currentSection.isEmpty() ? key : currentSection + "." + key;
                    UpdatableField match = fields.stream()
                        .filter(f -> f.fieldKey().equals(fullKey))
                        .findFirst().orElse(null);
                    if (match != null) {
                        String value = renderedVars.get(match.templateVar()) + match.valueSuffix();
                        // 保留行首缩进，用 toml 标准 "key = \"value\"" 格式重写
                        String leading = line.substring(0, line.length() - line.stripLeading().length());
                        result = leading + key + " = \"" + value + "\"";
                    }
                }
            }
            out.append(result);
            if (i < lines.length - 1) out.append("\n");
        }
        return out.toString();
    }

    private String updateJson(String content, List<UpdatableField> fields, Map<String, String> renderedVars) {
        try {
            JsonNode root = MAPPER.readTree(content);
            for (UpdatableField f : fields) {
                JsonPointer ptr = JsonPointer.compile(f.fieldKey());
                JsonNode parent = root.at(ptr.head());
                if (parent.isMissingNode() || !parent.isObject()) continue;
                String lastKey = ptr.last().getMatchingProperty();
                if (!parent.has(lastKey)) continue;  // 字段不存在不新增
                String value = renderedVars.get(f.templateVar()) + f.valueSuffix();
                ((ObjectNode) parent).put(lastKey, value);
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    // .env / ini 行级替换：匹配 KEY=VALUE，替换等号后内容
    // 注释行（# 或 ; 开头）、空行原样保留；不匹配的 KEY 不新增
    private String updateIni(String content, List<UpdatableField> fields, Map<String, String> renderedVars) {
        StringBuilder out = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String result = line;

            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";")) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx >= 0) {
                    String key = trimmed.substring(0, eqIdx).trim();
                    UpdatableField match = fields.stream()
                        .filter(f -> f.fieldKey().equals(key))
                        .findFirst().orElse(null);
                    if (match != null) {
                        String value = renderedVars.get(match.templateVar()) + match.valueSuffix();
                        result = key + "=" + value;
                    }
                }
            }
            out.append(result);
            if (i < lines.length - 1) out.append("\n");
        }
        return out.toString();
    }
}
