package com.ai8493.llmproxy.clients;

import com.ai8493.llmproxy.config.ConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClientConfigService {

    private static final String PROXY_BASE_URL = "http://localhost:8493";

    private final String apiKey;
    private final SpringTemplateEngine templateEngine;
    // 用 LinkedHashMap 保留注册顺序，让前端左列表显示顺序稳定（codex 在前）
    private final Map<String, ClientDefinition> clients = new LinkedHashMap<>();
    // 配置文件根目录，默认用户 HOME（codex 落 ~/.codex、gemini-cli 落 ~/.gemini）
    private String homeDir = System.getProperty("user.home");
    private final ConfigService configService;
    private final ConfigFieldUpdater configFieldUpdater;

    public ClientConfigService(
            @Value("${admin.password:123456}") String apiKey,
            @Qualifier("clientTemplateEngine") SpringTemplateEngine templateEngine,
            ConfigService configService,
            ConfigFieldUpdater configFieldUpdater) {
        this.apiKey = apiKey;
        this.templateEngine = templateEngine;
        this.configService = configService;
        this.configFieldUpdater = configFieldUpdater;
        register(new CodexClient(PROXY_BASE_URL, apiKey));
        register(new GeminiCliClient(PROXY_BASE_URL, apiKey));
    }

    private void register(ClientDefinition client) {
        clients.put(client.id(), client);
    }

    // 仅供测试用：替换 homeDir 隔离文件 IO，避免写穿开发机真实的 ~/.codex / ~/.gemini
    public void setHomeDirForTest(String dir) {
        this.homeDir = dir;
    }

    public List<ClientInfo> listClients() {
        return clients.values().stream()
            .map(c -> new ClientInfo(
                c.id(),
                c.displayName(),
                c.files().stream()
                    .map(f -> new ClientInfo.FileMeta(
                        f.filename(),
                        f.language(),
                        Path.of(homeDir, c.configSubdir(), f.filename()).toAbsolutePath().toString()))
                    .toList()))
            .toList();
    }

    public ReadResult readFile(String clientId, String filename) {
        ClientDefinition client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("客户端不存在: " + clientId);
        }
        ClientFile file = findFile(client, filename);
        if (file == null) {
            throw new IllegalArgumentException("文件不在白名单: " + filename);
        }
        return readOrRender(client, file);
    }

    // 文件存在性判断：codex 的 config.toml 单向耦合 auth.json
    // auth.json 缺失时 config.toml 视为不存在,让前端保存按钮恒可点
    private boolean fileExists(ClientDefinition client, ClientFile file) {
        Path path = Path.of(homeDir, client.configSubdir(), file.filename());
        if (!Files.exists(path)) return false;
        if ("codex".equals(client.id()) && "config.toml".equals(file.filename())) {
            return Files.exists(Path.of(homeDir, client.configSubdir(), "auth.json"));
        }
        return true;
    }

    // 读已有文件内容；不存在则渲染模板。client/file 已由调用方做白名单校验
    // 返回 ReadResult 携带 exists 标识，让前端区分"磁盘已有文件"与"模板渲染内容"
    private ReadResult readOrRender(ClientDefinition client, ClientFile file) {
        Path path = Path.of(homeDir, client.configSubdir(), file.filename());
        if (fileExists(client, file)) {
            try {
                String diskContent = Files.readString(path);
                // codex 的 config.toml 补缺校验;其他文件原样返回
                if ("codex".equals(client.id()) && "config.toml".equals(file.filename())) {
                    FilledResult r = fillMissingCodexConfigToml(diskContent, client, file);
                    return new ReadResult(r.content(), true, r.filled());
                }
                return new ReadResult(diskContent, true, false);
            } catch (IOException e) {
                throw new IllegalStateException("读取文件失败: " + path, e);
            }
        }
        return new ReadResult(renderTemplate(client, file), false, false);
    }

    public WriteResult writeFile(String clientId, String filename, String content) {
        ClientDefinition client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("客户端不存在: " + clientId);
        }
        ClientFile file = findFile(client, filename);
        if (file == null) {
            throw new IllegalArgumentException("文件不在白名单: " + filename);
        }

        // codex 的 config.toml 补缺校验;其他文件原样落盘
        String finalContent = content;
        if ("codex".equals(client.id()) && "config.toml".equals(filename)) {
            finalContent = fillMissingCodexConfigToml(content, client, file).content();
        }

        Path dir = Path.of(homeDir, client.configSubdir());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(filename), finalContent);
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败: " + dir.resolve(filename), e);
        }
        return new WriteResult(finalContent);
    }

    // 对单个配置文件应用代理默认值：读已有内容（不存在则渲染模板）→ 按 updatableFields 替换 → 写回
    // 返回更新后内容 + 是否发生替换（无代理字段的文件返回原内容 + updated=false）
    public ApplyResult applyProxyDefaults(String clientId, String filename) {
        ClientDefinition client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("客户端不存在: " + clientId);
        }
        ClientFile file = findFile(client, filename);
        if (file == null) {
            throw new IllegalArgumentException("文件不在白名单: " + filename);
        }

        String content = readOrRender(client, file).content();

        List<UpdatableField> fields = client.updatableFields().stream()
            .filter(f -> f.filename().equals(filename))
            .toList();
        if (fields.isEmpty()) {
            return new ApplyResult(content, false);
        }

        Map<String, String> vars = Map.of(
            "proxyBaseUrl", client.proxyBaseUrl(),
            "apiKey", client.apiKey(),
            "defaultModel", configService.findDefaultModelForClientProtocol(client.protocol())
        );

        String updated = configFieldUpdater.update(content, file.language(), fields, vars);
        Path path = Path.of(homeDir, client.configSubdir(), file.filename());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, updated);
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败: " + path, e);
        }
        return new ApplyResult(updated, true);
    }

    public record ApplyResult(String content, boolean updated) {}

    public record ReadResult(String content, boolean exists, boolean filled) {}

    public record WriteResult(String content) {}

    // 校验 codex config.toml 模板字段是否齐全,缺失则从模板渲染内容取值补进
    // 返回 FilledResult(content, filled),filled 表示是否发生过补缺
    private FilledResult fillMissingCodexConfigToml(String content, ClientDefinition client, ClientFile file) {
        String rendered = renderTemplate(client, file);
        List<TemplateField> templateFields = parseTomlFields(rendered);
        Set<String> existingKeys = collectExistingFullKeys(content);
        Set<String> existingSections = collectExistingSections(content);

        // 分组模板字段:顶层(section="") + 各 section
        List<TemplateField> topTemplateFields = new ArrayList<>();
        Map<String, List<TemplateField>> sectionTemplateFields = new LinkedHashMap<>();
        for (TemplateField tf : templateFields) {
            if (tf.section().isEmpty()) {
                topTemplateFields.add(tf);
            } else {
                sectionTemplateFields.computeIfAbsent(tf.section(), k -> new ArrayList<>()).add(tf);
            }
        }

        // 计算缺失
        List<TemplateField> missingTop = topTemplateFields.stream()
            .filter(tf -> !existingKeys.contains(tf.fullKey())).toList();
        Map<String, List<TemplateField>> missingInSection = new LinkedHashMap<>();
        Map<String, List<TemplateField>> missingWholeSection = new LinkedHashMap<>();
        for (var entry : sectionTemplateFields.entrySet()) {
            String section = entry.getKey();
            List<TemplateField> fields = entry.getValue();
            if (!existingSections.contains(section)) {
                missingWholeSection.put(section, fields);
            } else {
                List<TemplateField> missing = fields.stream()
                    .filter(tf -> !existingKeys.contains(tf.fullKey())).toList();
                if (!missing.isEmpty()) {
                    missingInSection.put(section, missing);
                }
            }
        }

        boolean filled = !missingTop.isEmpty() || !missingInSection.isEmpty() || !missingWholeSection.isEmpty();
        if (!filled) {
            return new FilledResult(content, false);
        }

        // 重建内容:逐行扫描,遇到 [section] 时(在 [section] 行之前)补全当前 section 的缺失字段
        // 顶层缺失字段:在第一个 [section] 行之前补(若无 section 则末尾补)
        // section 缺失字段:在该 section 之后(下一个 [section] 之前或文件末尾)补
        // section 完全缺失:文件末尾补 [section] 头 + 字段
        StringBuilder out = new StringBuilder();
        String currentSection = "";
        boolean topInserted = false;
        String[] lines = content.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                // 即将进入新 section,先补全当前 section 的缺失字段
                if (currentSection.isEmpty()) {
                    if (!topInserted && !missingTop.isEmpty()) {
                        ensureTrailingNewline(out);
                        for (TemplateField tf : missingTop) {
                            out.append(tf.rawLine()).append("\n");
                        }
                        topInserted = true;
                    }
                } else if (missingInSection.containsKey(currentSection)) {
                    ensureTrailingNewline(out);
                    for (TemplateField tf : missingInSection.get(currentSection)) {
                        out.append(tf.rawLine()).append("\n");
                    }
                }
                currentSection = trimmed.substring(1, trimmed.length() - 1);
            }

            out.append(line);
            if (i < lines.length - 1) out.append("\n");
        }

        // 文件末尾:补最后一个 section 的缺失字段
        if (!currentSection.isEmpty() && missingInSection.containsKey(currentSection)) {
            ensureTrailingNewline(out);
            for (TemplateField tf : missingInSection.get(currentSection)) {
                out.append(tf.rawLine()).append("\n");
            }
        }

        // 顶层未插入(文件无 section):补到末尾
        if (!topInserted && !missingTop.isEmpty()) {
            ensureTrailingNewline(out);
            for (TemplateField tf : missingTop) {
                out.append(tf.rawLine()).append("\n");
            }
            topInserted = true;
        }

        // 整段缺失的 section:补到文件末尾
        for (var entry : missingWholeSection.entrySet()) {
            String section = entry.getKey();
            List<TemplateField> fields = entry.getValue();
            ensureTrailingNewline(out);
            out.append("[").append(section).append("]\n");
            for (TemplateField tf : fields) {
                out.append(tf.rawLine()).append("\n");
            }
        }

        // 去掉末尾多余换行(保持与原文件末尾风格一致)
        String result = out.toString();
        if (result.endsWith("\n") && !content.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }

        return new FilledResult(result, true);
    }

    private void ensureTrailingNewline(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append("\n");
        }
    }

    // 解析 toml 内容为字段列表,按出现顺序保留。识别活动 key = value 行,
    // 跳过注释行、空行、section 头(section 头用于维护 currentSection 但不作为字段)
    private List<TemplateField> parseTomlFields(String tomlContent) {
        List<TemplateField> fields = new ArrayList<>();
        String currentSection = "";
        for (String line : tomlContent.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1);
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx < 0) continue;
            String key = trimmed.substring(0, eqIdx).trim();
            String fullKey = currentSection.isEmpty() ? key : currentSection + "." + key;
            fields.add(new TemplateField(currentSection, key, fullKey, line));
        }
        return fields;
    }

    // 收集实际内容中已存在的字段 fullKey 集合。活动字段与注释字段都算存在
    private Set<String> collectExistingFullKeys(String content) {
        Set<String> keys = new HashSet<>();
        String currentSection = "";
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1);
                continue;
            }
            if (trimmed.isEmpty()) continue;
            String body = trimmed;
            if (body.startsWith("#")) {
                body = body.substring(1).trim();
            }
            int eqIdx = body.indexOf('=');
            if (eqIdx < 0) continue;
            String key = body.substring(0, eqIdx).trim();
            String fullKey = currentSection.isEmpty() ? key : currentSection + "." + key;
            keys.add(fullKey);
        }
        return keys;
    }

    // 收集实际内容中已存在的 section 名集合(通过 [section] 头识别)
    private Set<String> collectExistingSections(String content) {
        Set<String> sections = new HashSet<>();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sections.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return sections;
    }

    private record FilledResult(String content, boolean filled) {}
    private record TemplateField(String section, String key, String fullKey, String rawLine) {}

    private String renderTemplate(ClientDefinition client, ClientFile file) {
        Context ctx = new Context();
        ctx.setVariable("proxyBaseUrl", client.proxyBaseUrl());
        ctx.setVariable("apiKey", client.apiKey());
        ctx.setVariable("defaultModel", configService.findDefaultModelForClientProtocol(client.protocol()));
        return templateEngine.process(file.templatePath() + templateSuffix(file), ctx);
    }

    private String templateSuffix(ClientFile file) {
        return switch (file.language()) {
            case "toml" -> ".toml";
            case "json" -> ".json";
            default -> "";
        };
    }

    private ClientFile findFile(ClientDefinition client, String filename) {
        return client.files().stream()
            .filter(f -> f.filename().equals(filename))
            .findFirst()
            .orElse(null);
    }
}
