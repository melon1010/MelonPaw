/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.runner.AgentSessionLogReader;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * QwenPaw console-compatible chat metadata endpoints.
 */
@RestController
@RequestMapping("/api/chats")
public class ChatsCompatController {

    private final ChatManager chatManager;
    private final AgentSessionLogReader sessionLogReader;

    public ChatsCompatController(ChatManager chatManager, AgentSessionLogReader sessionLogReader) {
        this.chatManager = chatManager;
        this.sessionLogReader = sessionLogReader;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listChats(@RequestParam(value = "user_id", required = false) String userId,
                                             @RequestParam(value = "channel", required = false) String channel,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(chatManager.list(agentId, userId, channel)));
    }

    @PostMapping
    public Mono<ResponseEntity<?>> createChat(@RequestBody(required = false) Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            String name = body != null ? stringValue(body.get("name"), stringValue(body.get("title"), "New Chat")) : "New Chat";
            String userId = body != null ? stringValue(body.get("user_id"), "default") : "default";
            String channel = body != null ? stringValue(body.get("channel"), "console") : "console";
            String sessionId = body != null ? stringValue(body.get("session_id"), null) : null;
            return ResponseEntity.ok(chatManager.create(agentId, name, userId, channel, sessionId));
        });
    }

    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<?>> getChat(@PathVariable String chatId,
                                           @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            ChatSpec spec = chatManager.get(agentId, chatId);
            if (spec == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("messages", messagesFor(agentId, spec));
            history.put("status", spec.getStatus() != null ? spec.getStatus() : "idle");
            return ResponseEntity.ok(history);
        });
    }

    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<?>> updateChat(@PathVariable String chatId, @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            ChatSpec spec = chatManager.get(agentId, chatId);
            if (spec == null) {
                return ResponseEntity.notFound().build();
            }
            if (body.containsKey("title")) spec.setName(String.valueOf(body.get("title")));
            if (body.containsKey("name")) spec.setName(String.valueOf(body.get("name")));
            if (body.get("pinned") instanceof Boolean pinned) spec.setPinned(pinned);
            return ResponseEntity.ok(chatManager.update(agentId, spec));
        });
    }

    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<?>> deleteChat(@PathVariable String chatId,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of(
                "success", chatManager.delete(agentId, chatId),
                "chat_id", chatId,
                "deleted_count", 1
        )));
    }

    @PostMapping("/batch-delete")
    public Mono<ResponseEntity<?>> batchDelete(@RequestBody List<String> chatIds,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            int count = 0;
            if (chatIds != null) {
                for (String chatId : chatIds) {
                    if (chatManager.delete(agentId, chatId)) count++;
                }
            }
            return ResponseEntity.ok(Map.of("success", true, "deleted_count", count));
        });
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private List<Map<String, Object>> messagesFor(String agentId, ChatSpec spec) {
        return sessionLogReader.readFrontendMessages(agentId, spec.getUserId(), spec.getChannel(), spec.getSessionId());
    }
}
