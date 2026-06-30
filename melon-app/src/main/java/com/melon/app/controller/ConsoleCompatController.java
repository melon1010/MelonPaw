/**
 * @author melon
 */
package com.melon.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.app.runner.SseEventMapper;
import com.melon.app.service.ApprovalService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * QwenPaw console-compatible endpoints used by the existing frontend.
 */
@RestController
@RequestMapping("/api/console")
public class ConsoleCompatController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleCompatController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunner agentRunner;
    private final SseEventMapper sseEventMapper;
    private final ChatManager chatManager;
    private final ApprovalService approvalService;

    public ConsoleCompatController(AgentRunner agentRunner, SseEventMapper sseEventMapper, ChatManager chatManager, ApprovalService approvalService) {
        this.agentRunner = agentRunner;
        this.sseEventMapper = sseEventMapper;
        this.chatManager = chatManager;
        this.approvalService = approvalService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String userId = stringValue(body.get("user_id"), headerUserId != null ? headerUserId : "default");
        String sessionId = stringValue(body.get("session_id"), "default");
        String channel = stringValue(body.get("channel"), "console");
        String text = extractInputText(body.get("input"));
        ChatSpec chat = chatManager.getOrCreateForSession(agentId, sessionId, titleFromText(text), null);
        String chatId = chat.getId();

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("agent_id", agentId);
        env.put("channel", channel);
        env.put("source", "console");
        env.put("session_id", sessionId);
        if (body.get("env_info") instanceof Map<?, ?> envInfo) {
            for (var entry : envInfo.entrySet()) {
                env.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        List<Msg> msgs = List.of(new UserMessage(text));
        chatManager.appendMessage(chatId, "user", text);
        StreamState streamState = new StreamState();
        return agentRunner.stream(agentId, msgs, userId, sessionId, env)
                .doOnSubscribe(s -> log.info("Console chat started: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId))
                .flatMapIterable(event -> mapFrontendEvent(event, streamState))
                .doFinally(signal -> {
                    if (!streamState.assistantText.isEmpty()) {
                        chatManager.appendMessage(chatId, "assistant", streamState.assistantText.toString());
                    }
                    log.info("Console chat finished: agent={}, session={}, chat={}, signal={}", agentId, sessionId, chatId, signal);
                })
                .onErrorResume(e -> {
                    log.error("Console chat failed: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId, e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data(frontendErrorJson(e))
                            .build());
                });
    }

    @PostMapping("/chat/stop")
    public Mono<ResponseEntity<?>> stopChat(@RequestParam(value = "chat_id", required = false) String chatId) {
        return Mono.just(ResponseEntity.ok(Map.of("stopped", true, "chat_id", chatId != null ? chatId : "")));
    }

    @GetMapping("/push-messages")
    public Mono<ResponseEntity<?>> pushMessages(@RequestParam(value = "session_id", required = false) String sessionId) {
        List<Map<String, Object>> pending = sessionId != null
                ? optionalList(approvalService.getPendingApproval(sessionId))
                : approvalService.getPendingApprovals();
        return Mono.just(ResponseEntity.ok(Map.of(
                "messages", List.of(),
                "pending_approvals", pending
        )));
    }

    @GetMapping("/inbox/events")
    public Mono<ResponseEntity<?>> inboxEvents() {
        return Mono.just(ResponseEntity.ok(Map.of("events", List.of())));
    }

    @PostMapping("/inbox/read")
    public Mono<ResponseEntity<?>> markInboxRead() {
        return Mono.just(ResponseEntity.ok(Map.of("updated", 0)));
    }

    @DeleteMapping("/inbox/events/{eventId}")
    public Mono<ResponseEntity<?>> deleteInboxEvent(@PathVariable String eventId) {
        return Mono.just(ResponseEntity.ok(Map.of("deleted", true, "trace_deleted", false, "run_id", "")));
    }

    @GetMapping("/inbox/traces/{runId}")
    public Mono<ResponseEntity<?>> inboxTrace(@PathVariable String runId) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("run_id", runId);
        trace.put("created_at", System.currentTimeMillis() / 1000.0);
        trace.put("completed_at", null);
        trace.put("status", "not_found");
        trace.put("meta", Map.of());
        trace.put("events", List.of());
        return Mono.just(ResponseEntity.ok(trace));
    }

    @GetMapping("/debug/backend-logs")
    public Mono<ResponseEntity<?>> backendLogs(@RequestParam(defaultValue = "200") int lines) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "path", "",
                "exists", false,
                "lines", Math.max(0, lines),
                "updated_at", 0,
                "size", 0,
                "content", ""
        )));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadCompat() {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Console upload is not implemented yet")));
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private List<ServerSentEvent<String>> mapFrontendEvent(AgentEvent event, StreamState state) {
        return switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> {
                String delta = ((TextBlockDeltaEvent) event).getDelta();
                state.assistantText.append(delta);
                yield withAssistantMessage(state, frontendTextDelta(state, delta, "in_progress"));
            }
            case THINKING_BLOCK_DELTA -> {
                ThinkingBlockDeltaEvent thinking = (ThinkingBlockDeltaEvent) event;
                yield withReasoningMessage(state, frontendThinkingDelta(state, thinking.getDelta(), "in_progress"));
            }
            case TOOL_CALL_START -> List.of(frontendToolCall((ToolCallStartEvent) event));
            case TOOL_RESULT_TEXT_DELTA -> List.of(frontendToolResultDelta((ToolResultTextDeltaEvent) event));
            case TOOL_RESULT_END -> List.of(frontendToolResult((ToolResultEndEvent) event));
            case AGENT_END -> List.of(frontendCompletedResponse(state));
            default -> List.of(frontendHeartbeat());
        };
    }

    private List<ServerSentEvent<String>> withAssistantMessage(StreamState state, ServerSentEvent<String> event) {
        if (state.assistantMessageCreated) {
            return List.of(event);
        }
        state.assistantMessageCreated = true;
        return List.of(frontendMessage(state.assistantMessageId, "message", "assistant", "in_progress"), event);
    }

    private List<ServerSentEvent<String>> withReasoningMessage(StreamState state, ServerSentEvent<String> event) {
        if (state.reasoningMessageCreated) {
            return List.of(event);
        }
        state.reasoningMessageCreated = true;
        return List.of(frontendMessage(state.reasoningMessageId, "reasoning", "assistant", "in_progress"), event);
    }

    private ServerSentEvent<String> frontendMessage(String messageId, String type, String role, String status) {
        return ServerSentEvent.<String>builder()
                .event("message")
                .data(toJson(runtimeMessage(messageId, type, role, status, List.of())))
                .build();
    }

    private ServerSentEvent<String> frontendTextDelta(StreamState state, String text, String status) {
        return frontendContent(state.assistantMessageId, "text", Map.of("text", text != null ? text : ""), true, status);
    }

    private ServerSentEvent<String> frontendThinkingDelta(StreamState state, String text, String status) {
        return frontendContent(state.reasoningMessageId, "text", Map.of("text", text != null ? text : ""), true, status);
    }

    private ServerSentEvent<String> frontendContent(String messageId, String type, Map<String, Object> fields, boolean delta, String status) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("object", "content");
        content.put("msg_id", messageId);
        content.put("type", type);
        content.put("status", status);
        content.put("delta", delta);
        content.putAll(fields);
        return ServerSentEvent.<String>builder().event("content").data(toJson(content)).build();
    }

    private ServerSentEvent<String> frontendCompletedResponse(StreamState state) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", state.responseId);
        response.put("object", "response");
        response.put("status", "completed");
        response.put("created_at", now);
        response.put("completed_at", now);
        response.put("output", List.of());
        response.put("error", null);
        return ServerSentEvent.<String>builder().event("response").data(toJson(response)).build();
    }

    private ServerSentEvent<String> frontendToolCall(ToolCallStartEvent event) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("status", "completed");
        content.put("data", Map.of(
                "name", event.getToolCallName(),
                "call_id", event.getToolCallId(),
                "arguments", Map.of()
        ));
        Map<String, Object> message = runtimeMessage(toolMessageId(event.getToolCallId()), "tool_call", "assistant", "in_progress", List.of(content));
        return ServerSentEvent.<String>builder().event("tool_call").data(toJson(message)).build();
    }

    private ServerSentEvent<String> frontendToolResultDelta(ToolResultTextDeltaEvent event) {
        return frontendContent(toolMessageId(event.getToolCallId()), "data", Map.of(
                "data", Map.of(
                        "name", event.getToolCallName(),
                        "call_id", event.getToolCallId(),
                        "output", Map.of("text", event.getDelta() != null ? event.getDelta() : "")
                )
        ), false, "in_progress");
    }

    private ServerSentEvent<String> frontendToolResult(ToolResultEndEvent event) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("status", "completed");
        content.put("data", Map.of(
                "name", event.getToolCallName(),
                "call_id", event.getToolCallId(),
                "output", Map.of("state", event.getState().name())
        ));
        Map<String, Object> message = runtimeMessage(toolMessageId(event.getToolCallId()), "tool_call_output", "tool", "completed", List.of(content));
        return ServerSentEvent.<String>builder().event("tool_result").data(toJson(message)).build();
    }

    private ServerSentEvent<String> frontendHeartbeat() {
        Map<String, Object> message = runtimeMessage("message_" + UUID.randomUUID(), "heartbeat", "assistant", "completed", List.of());
        return ServerSentEvent.<String>builder().event("heartbeat").data(toJson(message)).build();
    }

    private Map<String, Object> runtimeMessage(String id, String type, String role, String status, List<Map<String, Object>> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("object", "message");
        message.put("role", role);
        message.put("type", type);
        message.put("status", status);
        message.put("content", content);
        return message;
    }

    private String titleFromText(String text) {
        if (text == null || text.isBlank()) return "New Chat";
        String title = text.strip().replaceAll("\\s+", " ");
        return title.length() > 30 ? title.substring(0, 30) : title;
    }

    @SuppressWarnings("unchecked")
    private String extractInputText(Object input) {
        if (input == null) return "";
        if (input instanceof String s) return s;
        if (input instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    parts.add(extractContentText(content));
                } else {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join("\n", parts).trim();
        }
        if (input instanceof Map<?, ?> map) {
            return extractContentText(map.get("content"));
        }
        return String.valueOf(input);
    }

    private String extractContentText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            List<String> parts = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(String.valueOf(map.get("type")))) {
                    Object text = map.get("text");
                    if (text != null) parts.add(String.valueOf(text));
                }
            }
            return String.join("\n", parts);
        }
        return String.valueOf(content);
    }

    private List<Map<String, Object>> optionalList(Map<String, Object> value) {
        return value != null ? List.of(value) : List.of();
    }

    private String errorJson(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "{\"detail\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String frontendErrorJson(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Map<String, Object> error = runtimeMessage("message_" + UUID.randomUUID(), "error", "assistant", "failed", List.of());
        error.put("code", "CHAT_ERROR");
        error.put("message", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "response_" + UUID.randomUUID());
        response.put("object", "response");
        response.put("status", "failed");
        response.put("created_at", System.currentTimeMillis() / 1000);
        response.put("completed_at", System.currentTimeMillis() / 1000);
        response.put("output", List.of(error));
        response.put("error", Map.of("code", "CHAT_ERROR", "message", message));
        return toJson(response);
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return errorJson(e);
        }
    }

    private String toolMessageId(String toolCallId) {
        return "tool_" + (toolCallId != null && !toolCallId.isBlank() ? toolCallId : UUID.randomUUID());
    }

    private static class StreamState {
        final String responseId = "response_" + UUID.randomUUID();
        final String assistantMessageId = "message_" + UUID.randomUUID();
        final String reasoningMessageId = "reasoning_" + UUID.randomUUID();
        final StringBuilder assistantText = new StringBuilder();
        boolean assistantMessageCreated;
        boolean reasoningMessageCreated;
    }
}
