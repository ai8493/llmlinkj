package com.ai8493.llmproxy.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

// Responses 协议会话状态缓存:跨请求恢复 function_call item。
//
// Codex CLI 等客户端的 follow-up 请求常以 previous_response_id + function_call_output 形式发出,
// 缺少对应的 function_call item。DeepSeek/kimi 等后端要求 assistant tool_call 紧邻 tool result,
// 否则 400。本缓存在响应时记录 response_id -> output 中的 function_call items,在请求时
// enrichRequest 把缺失的 function_call 插入 input。
//
// 简化版:仅按 previous_response_id 恢复,不做 unique fallback。
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CACHED_RESPONSES = 512;

    // call item 类型(function_call/custom_tool_call/tool_search_call 等都算)
    private static final Set<String> CALL_ITEM_TYPES = Set.of(
        "function_call", "custom_tool_call", "tool_search_call");
    // call output 类型
    private static final Set<String> CALL_OUTPUT_TYPES = Set.of(
        "function_call_output", "custom_tool_call_output", "tool_search_output");
    // enrich 时从缓存补全的字段
    private static final List<String> ENRICH_FIELDS = List.of(
        "name", "namespace", "arguments", "input", "status", "execution",
        "reasoning_content", "reasoning");

    private final Map<String, CachedResponse> responses = new LinkedHashMap<>();
    private final Object lock = new Object();

    // 记录完整响应:从 output 数组提取 call items,按 response_id 缓存
    // 返回缓存的 call item 数量(0 表示无 call item 或已存在)
    public int recordResponse(String responseId, JsonNode output) {
        if (responseId == null || responseId.isEmpty() || output == null || !output.isArray()) {
            return 0;
        }
        List<Map.Entry<String, JsonNode>> calls = new ArrayList<>();
        for (JsonNode item : output) {
            Map.Entry<String, JsonNode> call = cachedCallItem(item);
            if (call != null) calls.add(call);
        }
        if (calls.isEmpty()) return 0;

        synchronized (lock) {
            CachedResponse cached = responses.computeIfAbsent(responseId, k -> new CachedResponse());
            for (var entry : calls) {
                cached.put(entry.getKey(), entry.getValue());
            }
            prune();
            return calls.size();
        }
    }

    // 流式单条记录:在 response.output_item.done 事件时调用
    public boolean recordCallItem(String responseId, JsonNode item) {
        if (responseId == null || responseId.isEmpty()) return false;
        Map.Entry<String, JsonNode> call = cachedCallItem(item);
        if (call == null) return false;

        synchronized (lock) {
            CachedResponse cached = responses.computeIfAbsent(responseId, k -> new CachedResponse());
            cached.put(call.getKey(), call.getValue());
            prune();
            return true;
        }
    }

    // 修改 body 的 input 字段:从 previous_response_id 恢复缺失的 function_call,
    // 并对已有的 function_call 补全缺失字段(reasoning_content 等)。
    // 返回 restored + enriched 数量。body 不是 ObjectNode 或无 input 字段时返回 0。
    public int enrichRequest(ObjectNode body) {
        if (body == null) return 0;
        JsonNode prevNode = body.get("previous_response_id");
        String previousResponseId = prevNode != null && prevNode.isTextual()
            ? prevNode.asText() : null;
        if (previousResponseId == null || previousResponseId.isEmpty()) return 0;

        CachedResponse cached;
        synchronized (lock) {
            cached = responses.get(previousResponseId);
        }
        if (cached == null || cached.isEmpty()) return 0;

        JsonNode inputNode = body.get("input");
        if (inputNode == null) return 0;

        // 统一转成 List 处理(可能是数组或单对象)
        List<JsonNode> items = new ArrayList<>();
        boolean wasObject = false;
        if (inputNode.isArray()) {
            inputNode.forEach(items::add);
        } else if (inputNode.isObject()) {
            items.add(inputNode);
            wasObject = true;
        } else {
            return 0;
        }

        // 收集 output_call_ids(input 中 function_call_output 引用的 call_id)
        Set<String> outputCallIds = new LinkedHashSet<>();
        Set<String> existingCallIds = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String type = itemType(item);
            String callId = responseItemCallId(item);
            if (callId == null) continue;
            if (CALL_OUTPUT_TYPES.contains(type)) {
                outputCallIds.add(callId);
            } else if (CALL_ITEM_TYPES.contains(type)) {
                existingCallIds.add(callId);
            }
        }

        // 计算 restore_group:在 outputCallIds 中但不在 existingCallIds 中的 call_id
        // 按缓存顺序排列(保持并行 tool calls 的原始顺序)
        List<Map.Entry<String, JsonNode>> restoreGroup = new ArrayList<>();
        Set<String> grouped = new HashSet<>();
        for (String callId : cached.callOrder) {
            if (outputCallIds.contains(callId)
                && !existingCallIds.contains(callId)
                && !grouped.contains(callId)) {
                JsonNode item = cached.callsById.get(callId);
                if (item != null) {
                    restoreGroup.add(Map.entry(callId, item));
                    grouped.add(callId);
                }
            }
        }

        List<JsonNode> newItems = new ArrayList<>();
        Set<String> seenCallIds = new HashSet<>();
        boolean restoreGroupInjected = false;
        int restored = 0;
        int enriched = 0;

        for (JsonNode item : items) {
            String type = itemType(item);
            if (CALL_ITEM_TYPES.contains(type)) {
                // 已有 function_call:用缓存补全缺失字段
                String callId = responseItemCallId(item);
                if (callId != null) {
                    JsonNode cachedItem = cached.callsById.get(callId);
                    if (cachedItem != null && enrichCallItemFromCache(item, cachedItem)) {
                        enriched++;
                    }
                    seenCallIds.add(callId);
                }
                newItems.add(item);
            } else if (CALL_OUTPUT_TYPES.contains(type)) {
                // function_call_output:首次遇到时注入 restore_group(并行 tool calls 一起恢复)
                if (!restoreGroupInjected && !restoreGroup.isEmpty()) {
                    for (var entry : restoreGroup) {
                        seenCallIds.add(entry.getKey());
                        newItems.add(entry.getValue());
                        restored++;
                    }
                    restoreGroupInjected = true;
                }
                // 单独恢复该 call_id(如果不在 restore_group 中且未被 seen)
                String callId = responseItemCallId(item);
                if (callId != null && !seenCallIds.contains(callId) && !grouped.contains(callId)) {
                    JsonNode cachedItem = cached.callsById.get(callId);
                    if (cachedItem != null) {
                        seenCallIds.add(callId);
                        newItems.add(cachedItem);
                        restored++;
                    }
                }
                newItems.add(item);
            } else {
                newItems.add(item);
            }
        }

        int changed = restored + enriched;
        if (changed > 0) {
            // 写回 body.input
            if (wasObject && newItems.size() == 1) {
                body.set("input", newItems.get(0));
            } else {
                ArrayNode arr = MAPPER.createArrayNode();
                newItems.forEach(arr::add);
                body.set("input", arr);
            }
            log.debug("enrichRequest: response_id={} restored={} enriched={}",
                previousResponseId, restored, enriched);
        }
        return changed;
    }

    // LRU 驱逐:超过 MAX_CACHED_RESPONSES 时删最老的
    private void prune() {
        while (responses.size() > MAX_CACHED_RESPONSES) {
            Iterator<String> it = responses.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    // 提取 call item 的 (call_id, item),非 call item 返回 null
    private static Map.Entry<String, JsonNode> cachedCallItem(JsonNode item) {
        if (item == null || !item.isObject()) return null;
        String type = itemType(item);
        if (!CALL_ITEM_TYPES.contains(type)) return null;
        String callId = responseItemCallId(item);
        if (callId == null) return null;
        return Map.entry(callId, item);
    }

    private static String itemType(JsonNode item) {
        JsonNode t = item.get("type");
        return t != null && t.isTextual() ? t.asText() : null;
    }

    // 提取 response item 的 call_id
    private static String responseItemCallId(JsonNode item) {
        if (item == null) return null;
        JsonNode callId = item.get("call_id");
        if (callId != null && callId.isTextual() && !callId.asText().isEmpty()) {
            return callId.asText();
        }
        return null;
    }

    // 用缓存补全已有 call item 的缺失字段(name/arguments/reasoning_content 等)
    private static boolean enrichCallItemFromCache(JsonNode item, JsonNode cached) {
        if (!(item instanceof ObjectNode obj) || !(cached.isObject())) return false;
        boolean changed = false;
        for (String key : ENRICH_FIELDS) {
            JsonNode current = obj.get(key);
            if (current != null && !isEmptyValue(current)) continue;
            JsonNode fromCache = cached.get(key);
            if (fromCache == null || isEmptyValue(fromCache)) continue;
            obj.set(key, fromCache);
            changed = true;
        }
        return changed;
    }

    private static boolean isEmptyValue(JsonNode value) {
        if (value == null) return true;
        if (value.isNull()) return true;
        if (value.isTextual() && value.asText().isEmpty()) return true;
        if (value.isArray() && value.isEmpty()) return true;
        if (value.isObject() && value.isEmpty()) return true;
        return false;
    }

    // 单个 response 的缓存:call_id -> item + 保序 call_order
    private static class CachedResponse {
        final Map<String, JsonNode> callsById = new LinkedHashMap<>();
        final List<String> callOrder = new ArrayList<>();

        void put(String callId, JsonNode item) {
            if (!callsById.containsKey(callId)) {
                callOrder.add(callId);
            }
            callsById.put(callId, item);
        }

        boolean isEmpty() {
            return callsById.isEmpty();
        }
    }
}
