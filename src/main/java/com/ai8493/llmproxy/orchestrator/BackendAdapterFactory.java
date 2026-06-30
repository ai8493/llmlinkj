package com.ai8493.llmproxy.orchestrator;

import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.adapter.gemini.GeminiBackendAdapter;
import com.ai8493.llmproxy.adapter.anthropic.AnthropicBackendAdapter;
import com.ai8493.llmproxy.adapter.openai.OpenAiBackendAdapter;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BackendAdapterFactory {

    private final BackendConfigRepository backendRepo;
    // 首次 get 时构造 + init，存入缓存。后续直接拿。
    // 配置热改通过 invalidate 失效单条缓存（由 ConfigService 在 backend 配置变更后调用）。
    // 注意：测试方法间共享 SpringContext 时 cache 也不回滚（@Transactional 只回滚 DB，不回滚 cache），
    //       但 ConfigService.saveBackend 会触发 invalidate 清理对应条目，避免跨测试污染。
    private final java.util.Map<String, BackendAdapter> cache = new ConcurrentHashMap<>();

    public BackendAdapterFactory(BackendConfigRepository backendRepo) {
        this.backendRepo = backendRepo;
    }

    public BackendAdapter get(String backendName) {
        // computeIfAbsent 保证 key 级原子，避免并发下重复构造 adapter（含 OkHttpClient 重资源）导致泄漏
        return cache.computeIfAbsent(backendName, k -> {
            var entity = backendRepo.findById(k)
                .orElseThrow(() -> new IllegalStateException("未找到后端: " + k));
            var config = toBackendConfig(entity);
            BackendAdapter adapter = switch (entity.protocol()) {
                case "gemini" -> new GeminiBackendAdapter(k);
                case "openai" -> new OpenAiBackendAdapter(k);
                case "anthropic" -> new AnthropicBackendAdapter(k);
                default -> throw new IllegalArgumentException("未知协议: " + entity.protocol());
            };
            adapter.init(config);
            return adapter;
        });
    }

    // 失效单个 backend 的 adapter 缓存，关闭其持有的 SDK client 资源。
    // 由 ConfigService 在 backend 配置变更后调用，使下次 get 重新构造。
    // 并发窄窗口：若另一线程正卡在 get 的 computeIfAbsent 内（已读旧 entity、未 put），
    // 此处 remove 返回 null 不 close，随后 get 会把基于旧配置的 adapter 放回 cache。
    // 管理操作频率低，可接受；需要强一致时再加全局锁。
    public void invalidate(String name) {
        BackendAdapter removed = cache.remove(name);
        if (removed != null) {
            removed.close();
        }
    }

    // BackendConfigEntity（DB，long 秒）→ BackendConfig（adapter 链所需，Duration）
    private static BackendConfig toBackendConfig(BackendConfigEntity e) {
        return new BackendConfig(
            e.protocol(),
            e.apiKey(),
            e.baseUrl(),
            e.defaultModel(),
            e.defaultMaxTokens(),
            Duration.ofSeconds(e.connectTimeout()),
            Duration.ofSeconds(e.readTimeout()),
            Duration.ofSeconds(e.writeTimeout()),
            new BackendConfig.PoolConfig(
                e.maxIdleConnections(),
                Duration.ofSeconds(e.keepAliveDuration())));
    }
}
