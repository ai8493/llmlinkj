package com.ai8493.llmproxy.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReasoningStore {

    private static final Logger log = LoggerFactory.getLogger(ReasoningStore.class);

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis = 30 * 60 * 1000; // 30 分钟

    /** 存储会话的 reasoning 内容 */
    public void remember(String key, String reasoningText) {
        if (key == null || reasoningText == null || reasoningText.isEmpty()) return;
        cache.put(key, new CachedEntry(reasoningText, System.currentTimeMillis()));
    }

    /** 获取会话的 reasoning 内容，过期返回 null */
    public String get(String key) {
        if (key == null) return null;
        CachedEntry entry = cache.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
            cache.remove(key);
            return null;
        }
        return entry.reasoningText;
    }

    /** 清理过期条目 */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().timestamp > ttlMillis);
    }

    private static class CachedEntry {
        final String reasoningText;
        final long timestamp;

        CachedEntry(String reasoningText, long timestamp) {
            this.reasoningText = reasoningText;
            this.timestamp = timestamp;
        }
    }
}
