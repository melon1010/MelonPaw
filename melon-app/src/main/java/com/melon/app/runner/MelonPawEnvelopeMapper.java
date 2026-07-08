package com.melon.app.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockEndEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.model.ChatUsage;
import org.springframework.http.codec.ServerSentEvent;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AgentScope Java events -> melonPaw Python-compatible streaming envelope.
 *
 * <p>This mirrors qwenpaw.runtime.envelope.Envelope closely enough for the
 * copied frontend's @agentscope-ai/chat renderer: messages are split by text,
 * reasoning, tool call and tool result, and tool payloads keep the Python
 * FunctionCall / FunctionCallOutput data shape.</p>
 */
public class MelonPawEnvelopeMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TURN_USAGE_META_KEY = "qwenpaw_turn_usage";
    private static final long DEFAULT_MAX_INPUT_LENGTH = 131_072L;

    private final String responseId = "response_" + UUID.randomUUID().toString().replace("-", "");
    private final String sessionId;
    private final long createdEpochSeconds = System.currentTimeMillis() / 1000;
    private final String createdAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    private int sequenceNumber;
    private String textMessageId = newMessageId();
    private boolean textMessageStarted;
    private final StringBuilder visibleAssistantText = new StringBuilder();
    private final List<Map<String, Object>> textContents = new ArrayList<>();
    private final Map<String, TextBlockState> textBlocks = new LinkedHashMap<>();
    private final Map<String, ReasoningState> reasoningBlocks = new HashMap<>();
    private final Map<String, ToolState> toolStates = new HashMap<>();
    private final Map<String, DataBlockState> dataBlocks = new HashMap<>();
    private final List<Map<String, Object>> outputMessages = new ArrayList<>();
    private Map<String, Object> usage;
    private boolean finalized;

    public MelonPawEnvelopeMapper(String sessionId) {
        this.sessionId = sessionId != null ? sessionId : "";
    }

    public List<ServerSentEvent<String>> start() {
        Map<String, Object> response = response("created");
        Map<String, Object> inProgress = new LinkedHashMap<>(response);
        inProgress.put("status", "in_progress");
        return List.of(sse("response", tag(response)), sse("response", tag(inProgress)));
    }

    public List<ServerSentEvent<String>> completeWithText(String text) {
        if (finalized) return List.of();
        finalized = true;
        List<ServerSentEvent<String>> events = new ArrayList<>(start());
        String messageId = newMessageId();
        Map<String, Object> content = textContent(text != null ? text : "", false, 0);
        events.add(messageEvent(messageId, "message", "assistant", "in_progress", List.of()));
        events.add(contentEvent(messageId, "text", Map.of("text", text != null ? text : ""), false, "completed", 0));
        events.add(completedMessageEvent(messageId, "message", "assistant", List.of(content)));
        Map<String, Object> completed = response("completed");
        completed.put("completed_at", System.currentTimeMillis() / 1000);
        completed.put("output", new ArrayList<>(outputMessages));
        events.add(sse("response", tag(completed)));
        return events;
    }

    public List<ServerSentEvent<String>> translate(AgentEvent event) {
        return switch (event.getType()) {
            case TEXT_BLOCK_START -> textStart((TextBlockStartEvent) event);
            case TEXT_BLOCK_DELTA -> textDelta((TextBlockDeltaEvent) event);
            case TEXT_BLOCK_END -> textEnd((TextBlockEndEvent) event);
            case THINKING_BLOCK_START -> thinkingStart((ThinkingBlockStartEvent) event);
            case THINKING_BLOCK_DELTA -> thinkingDelta((ThinkingBlockDeltaEvent) event);
            case THINKING_BLOCK_END -> thinkingEnd((ThinkingBlockEndEvent) event);
            case TOOL_CALL_START -> toolCallStart((ToolCallStartEvent) event);
            case TOOL_CALL_DELTA -> toolCallDelta((ToolCallDeltaEvent) event);
            case TOOL_CALL_END -> toolCallEnd((ToolCallEndEvent) event);
            case TOOL_RESULT_START -> toolResultStart((ToolResultStartEvent) event);
            case TOOL_RESULT_TEXT_DELTA -> toolResultTextDelta((ToolResultTextDeltaEvent) event);
            case TOOL_RESULT_DATA_DELTA -> toolResultDataDelta((ToolResultDataDeltaEvent) event);
            case TOOL_RESULT_END -> toolResultEnd((ToolResultEndEvent) event);
            case DATA_BLOCK_START -> dataStart((DataBlockStartEvent) event);
            case DATA_BLOCK_DELTA -> dataDelta((DataBlockDeltaEvent) event);
            case DATA_BLOCK_END -> dataEnd((DataBlockEndEvent) event);
            case MODEL_CALL_END -> modelCallEnd((ModelCallEndEvent) event);
            case AGENT_END -> finish();
            default -> List.of();
        };
    }

    public List<ServerSentEvent<String>> finish() {
        if (finalized) return List.of();
        finalized = true;
        List<ServerSentEvent<String>> events = new ArrayList<>();
        events.addAll(finalizeTextMessage());
        Map<String, Object> turnUsage = turnUsageSnapshot();
        decorateOutputMessages(turnUsage);
        Map<String, Object> completed = response("completed");
        completed.put("completed_at", System.currentTimeMillis() / 1000);
        completed.put("output", new ArrayList<>(outputMessages));
        if (turnUsage != null) {
            completed.put("usage", turnUsage.get("usage"));
            completed.put("context_usage", turnUsage.get("context_usage"));
        }
        events.add(sse("response", tag(completed)));
        if (turnUsage != null) events.add(sse("turn_usage", tag(new LinkedHashMap<>(turnUsage))));
        return events;
    }

    public List<ServerSentEvent<String>> error(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        List<ServerSentEvent<String>> events = new ArrayList<>();
        events.addAll(finalizeTextMessage());
        Map<String, Object> turnUsage = turnUsageSnapshot();
        decorateOutputMessages(turnUsage);
        Map<String, Object> failed = response("failed");
        failed.put("completed_at", System.currentTimeMillis() / 1000);
        failed.put("output", new ArrayList<>(outputMessages));
        if (turnUsage != null) {
            failed.put("usage", turnUsage.get("usage"));
            failed.put("context_usage", turnUsage.get("context_usage"));
        }
        failed.put("error", Map.of("code", "CHAT_ERROR", "message", message));
        events.add(sse("response", tag(failed)));
        if (turnUsage != null) events.add(sse("turn_usage", tag(new LinkedHashMap<>(turnUsage))));
        finalized = true;
        return events;
    }

    public String visibleAssistantText() {
        return visibleAssistantText.toString();
    }

    public List<Map<String, Object>> outputMessagesSnapshot() {
        return new ArrayList<>(outputMessages);
    }

    public List<Map<String, Object>> outputStateMessagesSnapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> output : outputMessages) {
            Map<String, Object> message = outputToStateMessage(output);
            if (!message.isEmpty()) result.add(message);
        }
        return result;
    }

    public Map<String, Object> turnUsageSnapshot() {
        Map<String, Object> usagePayload = usagePayload();
        Map<String, Object> contextUsage = contextUsagePayload(usagePayload);
        if (usagePayload == null && contextUsage == null) return null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "turn_usage");
        payload.put("usage", usagePayload);
        payload.put("context_usage", contextUsage);
        return payload;
    }

    private List<ServerSentEvent<String>> textStart(TextBlockStartEvent event) {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        ensureTextMessage(events);
        String blockId = blockId(event.getBlockId(), "text");
        textBlocks.putIfAbsent(blockId, new TextBlockState(textBlocks.size()));
        return events;
    }

    private List<ServerSentEvent<String>> textDelta(TextBlockDeltaEvent event) {
        String delta = event.getDelta() != null ? event.getDelta() : "";
        if (delta.isEmpty()) return List.of();
        List<ServerSentEvent<String>> events = new ArrayList<>();
        ensureTextMessage(events);
        String blockId = blockId(event.getBlockId(), "text");
        TextBlockState state = textBlocks.computeIfAbsent(blockId, ignored -> new TextBlockState(textBlocks.size()));
        state.text.append(delta);
        visibleAssistantText.append(delta);
        events.add(contentEvent(textMessageId, "text", Map.of("text", delta), true, "in_progress", state.index));
        return events;
    }

    private List<ServerSentEvent<String>> textEnd(TextBlockEndEvent event) {
        String blockId = blockId(event.getBlockId(), "text");
        TextBlockState state = textBlocks.get(blockId);
        if (state == null) return List.of();
        String text = state.text.toString();
        Map<String, Object> finalContent = textContent(text, false, state.index);
        if (!state.completed && !text.isEmpty()) {
            state.completed = true;
            textContents.add(finalContent);
        }
        return List.of(contentEvent(textMessageId, "text", Map.of("text", text), false, "completed", state.index));
    }

    private List<ServerSentEvent<String>> thinkingStart(ThinkingBlockStartEvent event) {
        String blockId = blockId(event.getBlockId(), "reasoning");
        ReasoningState state = reasoningBlocks.computeIfAbsent(blockId, ignored -> new ReasoningState());
        state.started = true;
        return List.of(messageEvent(state.messageId, "reasoning", "assistant", "in_progress", List.of()));
    }

    private List<ServerSentEvent<String>> thinkingDelta(ThinkingBlockDeltaEvent event) {
        String blockId = blockId(event.getBlockId(), "reasoning");
        ReasoningState state = reasoningBlocks.computeIfAbsent(blockId, ignored -> new ReasoningState());
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (!state.started) {
            state.started = true;
            events.add(messageEvent(state.messageId, "reasoning", "assistant", "in_progress", List.of()));
        }
        String delta = event.getDelta() != null ? event.getDelta() : "";
        state.text.append(delta);
        events.add(contentEvent(state.messageId, "text", Map.of("text", delta), true, "in_progress", 0));
        return events;
    }

    private List<ServerSentEvent<String>> thinkingEnd(ThinkingBlockEndEvent event) {
        String blockId = blockId(event.getBlockId(), "reasoning");
        ReasoningState state = reasoningBlocks.get(blockId);
        if (state == null) return List.of();
        String text = state.text.toString();
        Map<String, Object> finalContent = textContent(text, false, 0);
        reasoningBlocks.remove(blockId);
        return List.of(
                contentEvent(state.messageId, "text", Map.of("text", text), false, "completed", 0),
                completedMessageEvent(state.messageId, "reasoning", "assistant", List.of(finalContent))
        );
    }

    private List<ServerSentEvent<String>> toolCallStart(ToolCallStartEvent event) {
        List<ServerSentEvent<String>> events = new ArrayList<>(finalizeTextMessage());
        String callId = safeId(event.getToolCallId());
        String toolName = FrontendToolCompat.displayToolName(event.getToolCallName());
        ToolState state = new ToolState(callId, toolName, event.getToolCallName());
        toolStates.put(callId, state);
        Map<String, Object> stub = dataContent(functionCall(callId, toolName, ""), false, 0);
        events.add(messageEvent(state.callMessageId, "plugin_call", "assistant", "in_progress", List.of()));
        events.add(contentEvent(state.callMessageId, "data", Map.of("data", stub.get("data")), false, "in_progress", 0));
        return events;
    }

    private List<ServerSentEvent<String>> toolCallDelta(ToolCallDeltaEvent event) {
        String callId = safeId(event.getToolCallId());
        ToolState state = toolStates.computeIfAbsent(callId,
                ignored -> new ToolState(callId, FrontendToolCompat.displayToolName(event.getToolCallName()), event.getToolCallName()));
        state.arguments.append(event.getDelta() != null ? event.getDelta() : "");
        Map<String, Object> data = functionCall(callId, state.toolName, state.arguments.toString());
        return List.of(contentEvent(state.callMessageId, "data", Map.of("data", data), false, "in_progress", 0));
    }

    private List<ServerSentEvent<String>> toolCallEnd(ToolCallEndEvent event) {
        String callId = safeId(event.getToolCallId());
        ToolState state = toolStates.computeIfAbsent(callId,
                ignored -> new ToolState(callId, FrontendToolCompat.displayToolName(event.getToolCallName()), event.getToolCallName()));
        String args = FrontendToolCompat.argumentsJson(state.actualToolName, state.arguments.toString());
        Map<String, Object> finalContent = dataContent(functionCall(callId, state.toolName, args), false, 0);
        return List.of(
                contentEvent(state.callMessageId, "data", Map.of("data", finalContent.get("data")), false, "completed", 0),
                completedMessageEvent(state.callMessageId, "plugin_call", "assistant", List.of(finalContent))
        );
    }

    private List<ServerSentEvent<String>> toolResultStart(ToolResultStartEvent event) {
        String callId = safeId(event.getToolCallId());
        ToolState state = toolStates.computeIfAbsent(callId,
                ignored -> new ToolState(callId, FrontendToolCompat.displayToolName(event.getToolCallName()), event.getToolCallName()));
        Map<String, Object> stub = dataContent(functionCallOutput(callId, state.toolName, ""), false, 0);
        return List.of(
                messageEvent(state.resultMessageId, "plugin_call_output", "tool", "in_progress", List.of()),
                contentEvent(state.resultMessageId, "data", Map.of("data", stub.get("data")), false, "in_progress", 0)
        );
    }

    private List<ServerSentEvent<String>> toolResultTextDelta(ToolResultTextDeltaEvent event) {
        String callId = safeId(event.getToolCallId());
        ToolState state = toolStates.computeIfAbsent(callId,
                ignored -> new ToolState(callId, FrontendToolCompat.displayToolName(event.getToolCallName()), event.getToolCallName()));
        state.outputText.append(event.getDelta() != null ? event.getDelta() : "");
        Map<String, Object> data = functionCallOutput(callId, state.toolName, buildToolOutput(state));
        return List.of(contentEvent(state.resultMessageId, "data", Map.of("data", data), false, "in_progress", 0));
    }

    private List<ServerSentEvent<String>> toolResultDataDelta(ToolResultDataDeltaEvent event) {
        String callId = safeId(event.getToolCallId());
        ToolState state = toolStates.computeIfAbsent(callId,
                ignored -> new ToolState(callId, FrontendToolCompat.displayToolName(event.getToolCallName()), event.getToolCallName()));
        ContentBlock block = event.getData();
        if (block != null) {
            state.outputBlocks.add(JSON.convertValue(block, Map.class));
        }
        Map<String, Object> data = functionCallOutput(callId, state.toolName, buildToolOutput(state));
        return List.of(contentEvent(state.resultMessageId, "data", Map.of("data", data), false, "in_progress", 0));
    }

    private List<ServerSentEvent<String>> toolResultEnd(ToolResultEndEvent event) {
        String callId = safeId(event.getToolCallId());
        ToolState state = toolStates.computeIfAbsent(callId,
                ignored -> new ToolState(callId, FrontendToolCompat.displayToolName(event.getToolCallName()), event.getToolCallName()));
        Map<String, Object> data = functionCallOutput(callId, state.toolName, buildToolOutput(state));
        if (event.getState() != null) data.put("state", event.getState().name().toLowerCase());
        Map<String, Object> finalContent = dataContent(data, false, 0);
        return List.of(
                contentEvent(state.resultMessageId, "data", Map.of("data", data), false, "completed", 0),
                completedMessageEvent(state.resultMessageId, "plugin_call_output", "tool", List.of(finalContent))
        );
    }

    private List<ServerSentEvent<String>> dataStart(DataBlockStartEvent event) {
        dataBlocks.putIfAbsent(blockId(event.getBlockId(), "data"), new DataBlockState());
        return List.of();
    }

    private List<ServerSentEvent<String>> dataDelta(DataBlockDeltaEvent event) {
        DataBlockState state = dataBlocks.computeIfAbsent(blockId(event.getBlockId(), "data"), ignored -> new DataBlockState());
        state.data.append(event.getDelta() != null ? event.getDelta() : "");
        return List.of();
    }

    private List<ServerSentEvent<String>> dataEnd(DataBlockEndEvent event) {
        // AgentScope Java's data block event does not expose media type here;
        // keep parity for text/tool rendering and ignore opaque binary chunks.
        dataBlocks.remove(blockId(event.getBlockId(), "data"));
        return List.of();
    }

    private List<ServerSentEvent<String>> modelCallEnd(ModelCallEndEvent event) {
        ChatUsage chatUsage = event.getUsage();
        if (chatUsage != null) {
            usage = new LinkedHashMap<>();
            long inputTokens = chatUsage.getInputTokens();
            long outputTokens = chatUsage.getOutputTokens();
            long totalTokens = chatUsage.getTotalTokens();
            usage.put("prompt_tokens", inputTokens);
            usage.put("completion_tokens", outputTokens);
            usage.put("total_tokens", totalTokens);
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", outputTokens);
            usage.put("estimated", false);
        }
        return List.of();
    }

    private void ensureTextMessage(List<ServerSentEvent<String>> events) {
        if (textMessageStarted) return;
        textMessageStarted = true;
        events.add(messageEvent(textMessageId, "message", "assistant", "in_progress", List.of()));
    }

    private List<ServerSentEvent<String>> finalizeTextMessage() {
        if (!textMessageStarted) return List.of();
        for (TextBlockState state : textBlocks.values()) {
            if (!state.completed) {
                state.completed = true;
                if (!state.text.isEmpty()) {
                    textContents.add(textContent(state.text.toString(), false, state.index));
                }
            }
        }
        if (textContents.isEmpty()) {
            textMessageId = newMessageId();
            textMessageStarted = false;
            textBlocks.clear();
            return List.of();
        }
        List<ServerSentEvent<String>> events = List.of(completedMessageEvent(textMessageId, "message", "assistant", new ArrayList<>(textContents)));
        textMessageId = newMessageId();
        textMessageStarted = false;
        textContents.clear();
        textBlocks.clear();
        return events;
    }

    private ServerSentEvent<String> messageEvent(String id, String type, String role, String status, List<Map<String, Object>> content) {
        return sse("message", tag(runtimeMessage(id, type, role, status, content)));
    }

    private ServerSentEvent<String> completedMessageEvent(String id, String type, String role, List<Map<String, Object>> content) {
        Map<String, Object> message = runtimeMessage(id, type, role, "completed", content);
        outputMessages.add(new LinkedHashMap<>(message));
        return sse("message", tag(message));
    }

    private ServerSentEvent<String> contentEvent(String messageId, String type, Map<String, Object> fields, boolean delta, String status, int index) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("object", "content");
        content.put("msg_id", messageId);
        content.put("type", type);
        content.put("status", status);
        content.put("delta", delta);
        content.put("index", index);
        content.putAll(fields);
        return sse("content", tag(content));
    }

    private Map<String, Object> runtimeMessage(String id, String type, String role, String status, List<Map<String, Object>> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("object", "message");
        message.put("role", role);
        message.put("type", type);
        message.put("status", status);
        message.put("content", content);
        message.put("name", "assistant");
        if (usage != null) message.put("usage", usage);
        return message;
    }

    private Map<String, Object> response(String status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", responseId);
        response.put("object", "response");
        response.put("status", status);
        response.put("created_at", createdEpochSeconds);
        response.put("created_at_iso", createdAt);
        response.put("session_id", sessionId);
        response.put("output", List.of());
        response.put("error", null);
        if (usage != null) response.put("usage", usage);
        return response;
    }

    private Map<String, Object> usagePayload() {
        if (usage == null) {
            long estimated = estimateTextTokens();
            if (estimated <= 0) return null;
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("prompt_tokens", 0L);
            fallback.put("completion_tokens", estimated);
            fallback.put("total_tokens", estimated);
            fallback.put("estimated", true);
            return fallback;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        long prompt = numberValue(usage.get("prompt_tokens"), numberValue(usage.get("input_tokens"), 0L));
        long completion = numberValue(usage.get("completion_tokens"), numberValue(usage.get("output_tokens"), 0L));
        long total = numberValue(usage.get("total_tokens"), prompt + completion);
        payload.put("prompt_tokens", prompt);
        payload.put("completion_tokens", completion);
        payload.put("total_tokens", total > 0 ? total : prompt + completion);
        payload.put("estimated", Boolean.TRUE.equals(usage.get("estimated")));
        return payload;
    }

    private Map<String, Object> contextUsagePayload(Map<String, Object> usagePayload) {
        long estimatedTokens = 0L;
        if (usagePayload != null) {
            estimatedTokens = numberValue(usagePayload.get("prompt_tokens"), 0L);
            if (estimatedTokens <= 0) estimatedTokens = numberValue(usagePayload.get("total_tokens"), 0L);
        }
        if (estimatedTokens <= 0) estimatedTokens = estimateTextTokens();
        if (estimatedTokens <= 0) return null;

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("estimated_tokens", estimatedTokens);
        context.put("max_input_length", DEFAULT_MAX_INPUT_LENGTH);
        context.put("context_usage_ratio", Math.min(100.0, estimatedTokens * 100.0 / DEFAULT_MAX_INPUT_LENGTH));
        return context;
    }

    private void decorateOutputMessages(Map<String, Object> turnUsage) {
        if (turnUsage == null || outputMessages.isEmpty()) return;
        int lastIndex = outputMessages.size() - 1;
        decorateOutputMessage(outputMessages.get(lastIndex), turnUsage);
        for (int i = lastIndex; i >= 0; i--) {
            Map<String, Object> message = outputMessages.get(i);
            if ("assistant".equals(message.get("role"))) {
                decorateOutputMessage(message, turnUsage);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void decorateOutputMessage(Map<String, Object> message, Map<String, Object> turnUsage) {
        Map<String, Object> metadata = message.get("metadata") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
        metadata.put(TURN_USAGE_META_KEY, turnUsage);
        message.put("metadata", metadata);
        message.put("usage", turnUsage.get("usage"));
        message.put("context_usage", turnUsage.get("context_usage"));
    }

    private long estimateTextTokens() {
        long chars = visibleAssistantText.length();
        for (Map<String, Object> message : outputMessages) {
            chars += toJson(message.get("content")).length();
        }
        return chars <= 0 ? 0 : Math.max(1L, (chars + 3L) / 4L);
    }

    private long numberValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Map<String, Object> textContent(String text, boolean delta, int index) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("object", "content");
        content.put("status", delta ? "in_progress" : "completed");
        content.put("text", text != null ? text : "");
        content.put("delta", delta);
        content.put("index", index);
        return content;
    }

    private Map<String, Object> dataContent(Map<String, Object> data, boolean delta, int index) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("object", "content");
        content.put("status", delta ? "in_progress" : "completed");
        content.put("data", data);
        content.put("delta", delta);
        content.put("index", index);
        return content;
    }

    private Map<String, Object> functionCall(String callId, String name, String arguments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("call_id", callId);
        data.put("name", name);
        data.put("arguments", arguments != null ? arguments : "");
        return data;
    }

    private Map<String, Object> functionCallOutput(String callId, String name, String output) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("call_id", callId);
        data.put("name", name);
        data.put("output", output != null ? output : "");
        return data;
    }

    private Map<String, Object> outputToStateMessage(Map<String, Object> output) {
        if (output == null || output.isEmpty()) return Map.of();
        String type = string(output.get("type"));
        String id = string(output.get("id"));
        Object rawContent = output.get("content");
        if (!(rawContent instanceof List<?> content)) return Map.of();
        return switch (type) {
            case "message" -> textStateMessage(id, content, false);
            case "reasoning" -> textStateMessage(id, content, true);
            case "plugin_call" -> toolCallStateMessage(id, content);
            case "plugin_call_output" -> toolResultStateMessage(id, content);
            default -> Map.of();
        };
    }

    private Map<String, Object> textStateMessage(String id, List<?> content, boolean thinking) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : content) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            String text = string(raw.get("text"));
            if (text.isBlank()) continue;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", thinking ? "thinking" : "text");
            block.put(thinking ? "thinking" : "text", text);
            blocks.add(block);
        }
        if (blocks.isEmpty()) return Map.of();
        return stateMessage(id, "assistant", "assistant", blocks);
    }

    private Map<String, Object> toolCallStateMessage(String id, List<?> content) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : content) {
            Map<String, Object> data = contentData(item);
            if (data.isEmpty()) continue;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_call");
            block.put("id", valueOrDefault(string(data.get("call_id")), id));
            block.put("name", string(data.get("name")));
            block.put("input", nonNull(data.get("arguments")));
            block.put("state", "finished");
            blocks.add(block);
        }
        if (blocks.isEmpty()) return Map.of();
        return stateMessage(id, "assistant", "assistant", blocks);
    }

    private Map<String, Object> toolResultStateMessage(String id, List<?> content) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : content) {
            Map<String, Object> data = contentData(item);
            if (data.isEmpty()) continue;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("id", valueOrDefault(string(data.get("call_id")), id));
            block.put("name", string(data.get("name")));
            block.put("output", nonNull(data.get("output")));
            block.put("state", "success");
            blocks.add(block);
        }
        if (blocks.isEmpty()) return Map.of();
        return stateMessage(id, "tool", "tool", blocks);
    }

    private Map<String, Object> contentData(Object item) {
        if (!(item instanceof Map<?, ?> rawContent)) return Map.of();
        Object rawData = rawContent.get("data");
        if (!(rawData instanceof Map<?, ?> rawDataMap)) return Map.of();
        Map<String, Object> data = new LinkedHashMap<>();
        rawDataMap.forEach((key, value) -> data.put(String.valueOf(key), value));
        return data;
    }

    private Map<String, Object> stateMessage(String id, String role, String name, List<Map<String, Object>> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", valueOrDefault(id, "msg_" + UUID.randomUUID().toString().replace("-", "")));
        message.put("role", role);
        message.put("name", name);
        message.put("content", content);
        message.put("metadata", Map.of());
        return message;
    }

    private Object nonNull(Object value) {
        return value != null ? value : "";
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String buildToolOutput(ToolState state) {
        if (state.outputBlocks.isEmpty()) return state.outputText.toString();
        List<Object> blocks = new ArrayList<>(state.outputBlocks);
        if (!state.outputText.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", state.outputText.toString()));
        }
        try {
            return JSON.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            return state.outputText.toString();
        }
    }

    private Map<String, Object> tag(Map<String, Object> obj) {
        obj.put("sequence_number", ++sequenceNumber);
        return obj;
    }

    private ServerSentEvent<String> sse(String event, Map<String, Object> data) {
        return ServerSentEvent.<String>builder().event(event).data(toJson(data)).build();
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"detail\":\"JSON serialization failed\"}";
        }
    }

    private String blockId(String blockId, String prefix) {
        return Optional.ofNullable(blockId)
                .filter(s -> !s.isBlank())
                .orElse(prefix + "_default");
    }

    private String safeId(String id) {
        return Optional.ofNullable(id).filter(s -> !s.isBlank()).orElse(UUID.randomUUID().toString().replace("-", ""));
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class TextBlockState {
        final int index;
        final StringBuilder text = new StringBuilder();
        boolean completed;

        TextBlockState(int index) {
            this.index = index;
        }
    }

    private static final class ReasoningState {
        final String messageId = newMessageId();
        final StringBuilder text = new StringBuilder();
        boolean started;
    }

    private static final class ToolState {
        final String callId;
        final String toolName;
        final String actualToolName;
        final String callMessageId;
        final String resultMessageId;
        final StringBuilder arguments = new StringBuilder();
        final StringBuilder outputText = new StringBuilder();
        final List<Map<String, Object>> outputBlocks = new ArrayList<>();

        ToolState(String callId, String toolName, String actualToolName) {
            this.callId = callId;
            this.toolName = toolName;
            this.actualToolName = actualToolName;
            this.callMessageId = "tool_" + callId;
            this.resultMessageId = "tool_result_" + callId;
        }
    }

    private static final class DataBlockState {
        final StringBuilder data = new StringBuilder();
    }
}
