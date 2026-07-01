package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class QwenPawEnvelopeMapperSelfCheck {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private QwenPawEnvelopeMapperSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        QwenPawEnvelopeMapper mapper = new QwenPawEnvelopeMapper("s1");
        List<Map<String, Object>> payloads = new ArrayList<>();
        collect(payloads, mapper.start());
        collect(payloads, mapper.translate(new ThinkingBlockStartEvent("r1", "think1")));
        collect(payloads, mapper.translate(new ThinkingBlockDeltaEvent("r1", "think1", "a")));
        collect(payloads, mapper.translate(new ThinkingBlockEndEvent("r1", "think1")));
        collect(payloads, mapper.translate(new ThinkingBlockStartEvent("r1", "think2")));
        collect(payloads, mapper.translate(new ThinkingBlockDeltaEvent("r1", "think2", "b")));
        collect(payloads, mapper.translate(new ThinkingBlockEndEvent("r1", "think2")));
        collect(payloads, mapper.translate(new TextBlockStartEvent("r1", "text1")));
        collect(payloads, mapper.translate(new TextBlockDeltaEvent("r1", "text1", "I will run it.")));
        collect(payloads, mapper.translate(new TextBlockEndEvent("r1", "text1")));
        collect(payloads, mapper.translate(new ToolCallStartEvent("r1", "call_1", "execute")));
        collect(payloads, mapper.translate(new ToolCallDeltaEvent("r1", "call_1", "execute", "{\"command\":\"pwd\"}")));
        collect(payloads, mapper.translate(new ToolCallEndEvent("r1", "call_1", "execute")));
        collect(payloads, mapper.translate(new ToolResultStartEvent("r1", "call_1", "execute")));
        collect(payloads, mapper.translate(new ToolResultTextDeltaEvent("r1", "call_1", "execute", "Exit code: 0\n/tmp")));
        collect(payloads, mapper.translate(new ToolResultEndEvent("r1", "call_1", "execute", ToolResultState.SUCCESS)));
        collect(payloads, mapper.finish());
        verifyCompletedOutput(payloads, 4);

        QwenPawEnvelopeMapper missingBlockIds = new QwenPawEnvelopeMapper("s2");
        List<Map<String, Object>> missingPayloads = new ArrayList<>();
        collect(missingPayloads, missingBlockIds.start());
        collect(missingPayloads, missingBlockIds.translate(new ThinkingBlockStartEvent("r2", null)));
        collect(missingPayloads, missingBlockIds.translate(new ThinkingBlockDeltaEvent("r2", null, "thinking")));
        collect(missingPayloads, missingBlockIds.translate(new ThinkingBlockEndEvent("r2", null)));
        collect(missingPayloads, missingBlockIds.translate(new TextBlockStartEvent("r2", null)));
        collect(missingPayloads, missingBlockIds.translate(new TextBlockDeltaEvent("r2", null, "answer")));
        collect(missingPayloads, missingBlockIds.translate(new TextBlockEndEvent("r2", null)));
        collect(missingPayloads, missingBlockIds.finish());
        List<?> missingOutput = verifyCompletedOutput(missingPayloads, 2);
        if (!"reasoning".equals(((Map<?, ?>) missingOutput.get(0)).get("type"))
                || !"message".equals(((Map<?, ?>) missingOutput.get(1)).get("type"))) {
            throw new AssertionError("missing block ids changed output order: " + missingOutput);
        }
        long emptyCompletedMessages = missingOutput.stream()
                .map(Map.class::cast)
                .filter(p -> "message".equals(p.get("type")))
                .filter(p -> ((List<?>) p.get("content")).isEmpty())
                .count();
        if (emptyCompletedMessages > 0) {
            throw new AssertionError("empty completed message leaked into final output: " + missingOutput);
        }

        long reasoningCompleted = payloads.stream()
                .filter(p -> "message".equals(p.get("object")))
                .filter(p -> "reasoning".equals(p.get("type")))
                .filter(p -> "completed".equals(p.get("status")))
                .count();
        if (reasoningCompleted != 2) {
            throw new AssertionError("expected two reasoning messages, got " + reasoningCompleted);
        }

        Map<String, Object> toolCall = payloads.stream()
                .filter(p -> "message".equals(p.get("object")))
                .filter(p -> "plugin_call".equals(p.get("type")))
                .filter(p -> "completed".equals(p.get("status")))
                .findFirst()
                .orElseThrow();
        Map<?, ?> callData = data(toolCall);
        if (!"execute_shell_command".equals(callData.get("name"))) {
            throw new AssertionError("tool name not normalized: " + callData);
        }
        if (!(callData.get("arguments") instanceof String)) {
            throw new AssertionError("arguments must be JSON string: " + callData);
        }

        Map<String, Object> toolResult = payloads.stream()
                .filter(p -> "message".equals(p.get("object")))
                .filter(p -> "plugin_call_output".equals(p.get("type")))
                .filter(p -> "completed".equals(p.get("status")))
                .findFirst()
                .orElseThrow();
        Map<?, ?> resultData = data(toolResult);
        if (!(resultData.get("output") instanceof String)) {
            throw new AssertionError("output must be a string: " + resultData);
        }
        if (!String.valueOf(resultData.get("output")).contains("/tmp")) {
            throw new AssertionError("output text missing: " + resultData);
        }

    }

    private static List<?> verifyCompletedOutput(List<Map<String, Object>> payloads, int minSize) {
        Map<String, Object> finalResponse = payloads.stream()
                .filter(p -> "response".equals(p.get("object")))
                .filter(p -> "completed".equals(p.get("status")))
                .findFirst()
                .orElseThrow();
        Object output = finalResponse.get("output");
        if (!(output instanceof List<?> items) || items.size() < minSize) {
            throw new AssertionError("final response output should contain completed messages: " + finalResponse);
        }
        return items;
    }

    private static void collect(List<Map<String, Object>> payloads, List<ServerSentEvent<String>> events) throws Exception {
        for (ServerSentEvent<String> event : events) {
            payloads.add(JSON.readValue(event.data(), MAP));
        }
    }

    private static Map<?, ?> data(Map<String, Object> message) {
        List<?> content = (List<?>) message.get("content");
        return (Map<?, ?>) ((Map<?, ?>) content.get(0)).get("data");
    }
}
