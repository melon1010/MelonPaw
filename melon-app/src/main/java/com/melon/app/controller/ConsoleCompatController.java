/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.SseEventMapper;
import com.melon.app.service.ApprovalService;
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
        String chatId = stringValue(body.get("chat_id"), sessionId);
        String channel = stringValue(body.get("channel"), "console");
        String text = extractInputText(body.get("input"));

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
        StringBuilder assistantText = new StringBuilder();
        return agentRunner.stream(agentId, msgs, userId, sessionId, env)
                .doOnSubscribe(s -> log.info("Console chat started: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId))
                .map(sseEventMapper::map)
                .doOnNext(event -> {
                    if ("text_delta".equals(event.event()) && event.data() != null) {
                        assistantText.append(event.data());
                    }
                })
                .doFinally(signal -> {
                    if (!assistantText.isEmpty()) {
                        chatManager.appendMessage(chatId, "assistant", assistantText.toString());
                    }
                    log.info("Console chat finished: agent={}, session={}, chat={}, signal={}", agentId, sessionId, chatId, signal);
                })
                .onErrorResume(e -> {
                    log.error("Console chat failed: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId, e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data(errorJson(e))
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
}
