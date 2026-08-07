package com.ai8493.llmproxy.adapter.openai;

import java.util.*;

/**
 * 记录 Responses 入站协议中 custom/namespace 工具到 function 代理工具的映射关系，
 * 用于响应侧还原。
 */
public class ToolRemapContext {

    public enum Kind { APPLY_PATCH, RAW }

    // P3-14: 保留原始 description 用于响应侧还原
    public record CustomSpec(String originalName, Kind kind, String originalDescription) {}

    public record NamespaceSpec(String originalName, String namespace) {}

    final Map<String, CustomSpec> customTools = new HashMap<>();
    final Map<String, NamespaceSpec> namespaceTools = new HashMap<>();
    final Map<String, String> aliasToNamespace = new HashMap<>();
    final Map<String, String> namespaceToAlias = new HashMap<>();
    private int namespaceCounter = 0;

    public boolean isEmpty() {
        return customTools.isEmpty() && namespaceTools.isEmpty();
    }

    public boolean isCustomProxy(String functionName) {
        return customTools.containsKey(functionName);
    }

    public CustomSpec getCustomSpec(String functionName) {
        return customTools.get(functionName);
    }

    public NamespaceSpec getNamespaceSpec(String functionName) {
        return namespaceTools.get(functionName);
    }

    void putCustom(String proxyName, String originalName, Kind kind) {
        customTools.put(proxyName, new CustomSpec(originalName, kind, null));
    }

    void putCustom(String proxyName, String originalName, Kind kind, String originalDescription) {
        customTools.put(proxyName, new CustomSpec(originalName, kind, originalDescription));
    }

    void putNamespace(String flatName, String originalName, String namespace) {
        namespaceTools.put(flatName, new NamespaceSpec(originalName, namespace));
    }

    void putNamespace(String flatName, String originalName, String namespace, String alias) {
        namespaceTools.put(flatName, new NamespaceSpec(originalName, namespace));
        aliasToNamespace.put(alias, namespace);
    }

    public String generateAlias(String namespace) {
        return namespaceToAlias.computeIfAbsent(namespace, k -> "ns" + (namespaceCounter++));
    }

    public String getNamespaceByAlias(String alias) {
        return aliasToNamespace.get(alias);
    }
}
