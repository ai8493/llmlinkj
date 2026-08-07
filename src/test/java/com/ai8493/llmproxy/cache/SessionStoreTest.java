package com.ai8493.llmproxy.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    private final SessionStore store = new SessionStore();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode callItem(String callId, String name, String reasoning) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "function_call");
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", "{}");
        if (reasoning != null) item.put("reasoning_content", reasoning);
        return item;
    }

    private JsonNode outputItem(String callId, String output) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", output);
        return item;
    }

    private JsonNode responseWithOutput(String responseId, JsonNode... items) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("id", responseId);
        var arr = mapper.createArrayNode();
        for (var item : items) arr.add(item);
        resp.set("output", arr);
        return resp;
    }

    @Test
    void shouldRestoreFunctionCallFromPreviousResponse() {
        // recordResponse 缓存 resp_1 的 function_call
        store.recordResponse("resp_1",
            responseWithOutput("resp_1", callItem("call_1", "read_file", "Need to inspect the file.")).get("output"));

        // enrichRequest: previous_response_id + function_call_output -> 恢复 function_call
        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_1");
        var input = mapper.createArrayNode();
        input.add(outputItem("call_1", "ok"));
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(1);
        JsonNode result = body.get("input");
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).get("type").asText()).isEqualTo("function_call");
        assertThat(result.get(0).get("reasoning_content").asText()).isEqualTo("Need to inspect the file.");
        assertThat(result.get(1).get("type").asText()).isEqualTo("function_call_output");
    }

    @Test
    void shouldNotRestoreWithoutPreviousResponseId() {
        // 无 previous_response_id 时不恢复(简化版不做 unique fallback)
        store.recordResponse("resp_1",
            responseWithOutput("resp_1", callItem("call_1", "read_file", "reasoning")).get("output"));

        ObjectNode body = mapper.createObjectNode();
        var input = mapper.createArrayNode();
        input.add(outputItem("call_1", "ok"));
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(0);
        assertThat(body.get("input").size()).isEqualTo(1);
        assertThat(body.get("input").get(0).get("type").asText()).isEqualTo("function_call_output");
    }

    @Test
    void shouldNotRestoreWhenPreviousResponseIdNotCached() {
        store.recordResponse("resp_1",
            responseWithOutput("resp_1", callItem("call_1", "read_file", "reasoning")).get("output"));

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_missing");
        var input = mapper.createArrayNode();
        input.add(outputItem("call_1", "ok"));
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(0);
        assertThat(body.get("input").size()).isEqualTo(1);
    }

    @Test
    void shouldEnrichExistingFunctionCallMissingReasoning() {
        // 已有 function_call 但缺 reasoning_content,从缓存补全
        store.recordResponse("resp_1",
            responseWithOutput("resp_1", callItem("call_1", "read_file", "Need to inspect the file.")).get("output"));

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_1");
        var input = mapper.createArrayNode();
        // 已有 function_call 但无 reasoning_content
        input.add(callItem("call_1", "read_file", null));
        input.add(outputItem("call_1", "ok"));
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(1);
        JsonNode result = body.get("input");
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).get("reasoning_content").asText()).isEqualTo("Need to inspect the file.");
    }

    @Test
    void shouldRestoreParallelToolCallsAsGroup() {
        // 并行 tool calls:两个 function_call_output,恢复两个 function_call
        store.recordResponse("resp_1",
            responseWithOutput("resp_1",
                callItem("call_1", "first", "Need both tools."),
                callItem("call_2", "second", "Need both tools.")).get("output"));

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_1");
        var input = mapper.createArrayNode();
        input.add(outputItem("call_1", "one"));
        input.add(outputItem("call_2", "two"));
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(2);
        JsonNode result = body.get("input");
        assertThat(result.size()).isEqualTo(4);
        assertThat(result.get(0).get("type").asText()).isEqualTo("function_call");
        assertThat(result.get(0).get("call_id").asText()).isEqualTo("call_1");
        assertThat(result.get(1).get("type").asText()).isEqualTo("function_call");
        assertThat(result.get(1).get("call_id").asText()).isEqualTo("call_2");
        assertThat(result.get(2).get("type").asText()).isEqualTo("function_call_output");
        assertThat(result.get(3).get("type").asText()).isEqualTo("function_call_output");
    }

    @Test
    void shouldRestoreCustomToolCallAndToolSearchCall() {
        // custom_tool_call + tool_search_call 同样支持
        ObjectNode customCall = mapper.createObjectNode();
        customCall.put("type", "custom_tool_call");
        customCall.put("call_id", "call_patch");
        customCall.put("name", "apply_patch");
        customCall.put("input", "*** Begin Patch\n*** End Patch");
        customCall.put("reasoning_content", "Need to patch.");

        ObjectNode toolSearchCall = mapper.createObjectNode();
        toolSearchCall.put("type", "tool_search_call");
        toolSearchCall.put("call_id", "call_search");
        toolSearchCall.put("status", "completed");
        toolSearchCall.put("reasoning_content", "Need to discover tools.");

        store.recordResponse("resp_1",
            responseWithOutput("resp_1", customCall, toolSearchCall).get("output"));

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_1");
        var input = mapper.createArrayNode();
        ObjectNode customOut = mapper.createObjectNode();
        customOut.put("type", "custom_tool_call_output");
        customOut.put("call_id", "call_patch");
        customOut.put("output", "patched");
        input.add(customOut);
        ObjectNode searchOut = mapper.createObjectNode();
        searchOut.put("type", "tool_search_output");
        searchOut.put("call_id", "call_search");
        searchOut.put("tools", mapper.createArrayNode());
        input.add(searchOut);
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(2);
        JsonNode result = body.get("input");
        assertThat(result.size()).isEqualTo(4);
        assertThat(result.get(0).get("type").asText()).isEqualTo("custom_tool_call");
        assertThat(result.get(1).get("type").asText()).isEqualTo("tool_search_call");
        assertThat(result.get(2).get("type").asText()).isEqualTo("custom_tool_call_output");
        assertThat(result.get(3).get("type").asText()).isEqualTo("tool_search_output");
    }

    @Test
    void shouldRecordCallItemAndRestore() {
        // 流式 recordCallItem 单条记录
        store.recordCallItem("resp_stream", callItem("call_1", "read_file", "Need a file."));

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_stream");
        var input = mapper.createArrayNode();
        input.add(outputItem("call_1", "ok"));
        body.set("input", input);

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(1);
        assertThat(body.get("input").get(0).get("reasoning_content").asText()).isEqualTo("Need a file.");
    }

    @Test
    void shouldHandleInputAsSingleObject() {
        // input 是单对象(非数组)时也能处理
        store.recordResponse("resp_1",
            responseWithOutput("resp_1", callItem("call_1", "read_file", "reasoning")).get("output"));

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_1");
        body.set("input", outputItem("call_1", "ok"));

        int changed = store.enrichRequest(body);

        assertThat(changed).isEqualTo(1);
        // 单对象输入 + 恢复后变多元素,应转为数组
        assertThat(body.get("input").isArray()).isTrue();
        assertThat(body.get("input").size()).isEqualTo(2);
    }

    @Test
    void shouldEvictOldestWhenExceedingLimit() {
        // 超过 512 个 response 时,最老的被驱逐
        for (int i = 0; i < 513; i++) {
            store.recordResponse("resp_" + i,
                responseWithOutput("resp_" + i, callItem("call_" + i, "fn", "r")).get("output"));
        }

        // resp_0 应该已被驱逐
        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_0");
        var input = mapper.createArrayNode();
        input.add(outputItem("call_0", "ok"));
        body.set("input", input);
        assertThat(store.enrichRequest(body)).isEqualTo(0);

        // resp_512 仍存在
        ObjectNode body2 = mapper.createObjectNode();
        body2.put("previous_response_id", "resp_512");
        var input2 = mapper.createArrayNode();
        input2.add(outputItem("call_512", "ok"));
        body2.set("input", input2);
        assertThat(store.enrichRequest(body2)).isEqualTo(1);
    }

    @Test
    void shouldIgnoreResponseWithoutCallItems() {
        // output 中无 call item 时,不缓存
        ObjectNode messageItem = mapper.createObjectNode();
        messageItem.put("type", "message");
        messageItem.put("role", "assistant");
        messageItem.put("content", "hello");

        int count = store.recordResponse("resp_1",
            responseWithOutput("resp_1", messageItem).get("output"));

        assertThat(count).isEqualTo(0);

        ObjectNode body = mapper.createObjectNode();
        body.put("previous_response_id", "resp_1");
        var input = mapper.createArrayNode();
        input.add(outputItem("call_x", "ok"));
        body.set("input", input);
        assertThat(store.enrichRequest(body)).isEqualTo(0);
    }
}
