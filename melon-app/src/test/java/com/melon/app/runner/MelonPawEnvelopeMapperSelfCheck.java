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
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultState;
import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MelonPawEnvelopeMapperSelfCheck {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private MelonPawEnvelopeMapperSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        MelonPawEnvelopeMapper mapper = new MelonPawEnvelopeMapper("s1");
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

        MelonPawEnvelopeMapper missingBlockIds = new MelonPawEnvelopeMapper("s2");
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

        MelonPawEnvelopeMapper mediaMapper = new MelonPawEnvelopeMapper("s-media");
        List<Map<String, Object>> mediaPayloads = new ArrayList<>();
        collect(mediaPayloads, mediaMapper.start());
        collect(mediaPayloads, mediaMapper.translate(new ToolCallStartEvent("r-media", "call-media", "browser_use")));
        collect(mediaPayloads, mediaMapper.translate(new ToolCallEndEvent("r-media", "call-media", "browser_use")));
        collect(mediaPayloads, mediaMapper.translate(new ToolResultStartEvent("r-media", "call-media", "browser_use")));
        collect(mediaPayloads, mediaMapper.translate(new ToolResultDataDeltaEvent("r-media", "call-media", "browser_use",
                ImageBlock.builder().source(Base64Source.builder().mediaType("image/png").data("AQI=").build()).build())));
        collect(mediaPayloads, mediaMapper.translate(new ToolResultEndEvent("r-media", "call-media", "browser_use", ToolResultState.SUCCESS)));
        collect(mediaPayloads, mediaMapper.finish());
        Map<String, Object> mediaResult = mediaPayloads.stream()
                .filter(p -> "message".equals(p.get("object")))
                .filter(p -> "plugin_call_output".equals(p.get("type")))
                .filter(p -> "completed".equals(p.get("status")))
                .findFirst()
                .orElseThrow();
        if (!String.valueOf(data(mediaResult).get("output")).contains("\"type\":\"image\"")) {
            throw new AssertionError("media tool result was not frontend-compatible: " + mediaResult);
        }

        MelonPawEnvelopeMapper officialToolMapper = new MelonPawEnvelopeMapper("s3");
        List<Map<String, Object>> officialPayloads = new ArrayList<>();
        collect(officialPayloads, officialToolMapper.start());
        collect(officialPayloads, officialToolMapper.translate(new ToolCallStartEvent("r3", "call_agent", "agent_spawn")));
        collect(officialPayloads, officialToolMapper.translate(new ToolCallDeltaEvent("r3", "call_agent", "agent_spawn",
                "{\"agent_id\":\"general-purpose\",\"task\":\"inspect code\",\"timeout_seconds\":0}")));
        collect(officialPayloads, officialToolMapper.translate(new ToolCallEndEvent("r3", "call_agent", "agent_spawn")));
        collect(officialPayloads, officialToolMapper.finish());
        Map<String, Object> officialToolCall = officialPayloads.stream()
                .filter(p -> "message".equals(p.get("object")))
                .filter(p -> "plugin_call".equals(p.get("type")))
                .filter(p -> "completed".equals(p.get("status")))
                .findFirst()
                .orElseThrow();
        Map<?, ?> officialCallData = data(officialToolCall);
        if (!"submit_to_agent".equals(officialCallData.get("name"))) {
            throw new AssertionError("official subagent tool name not frontend-compatible: " + officialCallData);
        }
        Map<?, ?> officialArgs = JSON.readValue(String.valueOf(officialCallData.get("arguments")), MAP);
        if (!"general-purpose".equals(officialArgs.get("to_agent"))
                || !"inspect code".equals(officialArgs.get("text"))
                || !Integer.valueOf(0).equals(officialArgs.get("timeout"))) {
            throw new AssertionError("official subagent args not frontend-compatible: " + officialArgs);
        }

        Map<String, Object> skillArgs = FrontendToolCompat.parseArguments("materialize_skill",
                "{\"skill_name\":\"pptx\",\"file_path\":\"SKILL.md\"}");
        if (!"pptx".equals(skillArgs.get("name")) || !"pptx".equals(skillArgs.get("skill_name"))) {
            throw new AssertionError("materialize_skill args not frontend-compatible: " + skillArgs);
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
