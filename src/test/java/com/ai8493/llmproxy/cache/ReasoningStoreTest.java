package com.ai8493.llmproxy.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningStoreTest {

    private final ReasoningStore store = new ReasoningStore();

    @Test
    void 基本读写() {
        store.remember("key1", "hello");
        assertThat(store.get("key1")).isEqualTo("hello");
    }

    @Test
    void 不同key不串扰() {
        store.remember("key1", "value1");
        store.remember("key2", "value2");
        assertThat(store.get("key1")).isEqualTo("value1");
        assertThat(store.get("key2")).isEqualTo("value2");
    }

    @Test
    void 未存储返回null() {
        assertThat(store.get("nonexistent")).isNull();
    }

    @Test
    void nullKey处理() {
        store.remember(null, "test");
        assertThat(store.get(null)).isNull();
    }

    @Test
    void 空reasoning不存储() {
        store.remember("key1", "");
        assertThat(store.get("key1")).isNull();
    }

    @Test
    void 覆盖更新() {
        store.remember("key1", "old");
        store.remember("key1", "new");
        assertThat(store.get("key1")).isEqualTo("new");
    }

    @Test
    void evictExpired清理过期条目() {
        store.remember("key1", "test");
        store.evictExpired();
        assertThat(store.get("key1")).isEqualTo("test"); // 未过期（TTL=30分钟）
    }
}
