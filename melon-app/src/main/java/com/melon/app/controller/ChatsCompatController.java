/**
 * @author melon
 */
package com.melon.app.controller;

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

    public ChatsCompatController(ChatManager chatManager) {
        this.chatManager = chatManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listChats(@RequestParam(value = "user_id", required = false) String userId,
                                             @RequestParam(value = "channel", required = false) String channel) {
        return Mono.fromCallable(() -> ResponseEntity.ok(chatManager.list()));
    }

    @PostMapping
    public Mono<ResponseEntity<?>> createChat(@RequestBody(required = false) Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            String title = body != null ? stringValue(body.get("title"), stringValue(body.get("name"), "New Chat")) : "New Chat";
            String model = body != null ? stringValue(body.get("model"), null) : null;
            return ResponseEntity.ok(chatManager.create(agentId, title, model));
        });
    }

    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<?>> getChat(@PathVariable String chatId) {
        return Mono.fromCallable(() -> {
            ChatSpec spec = chatManager.get(chatId);
            if (spec == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("id", spec.getId());
            history.put("chat_id", spec.getId());
            history.put("session_id", spec.getSessionId() != null ? spec.getSessionId() : spec.getId());
            history.put("agent_id", spec.getAgentId());
            history.put("title", spec.getTitle());
            history.put("name", spec.getTitle());
            history.put("created_at", spec.getCreatedAt());
            history.put("updated_at", spec.getUpdatedAt());
            history.put("status", "idle");
            history.put("messages", spec.getMessages());
            return ResponseEntity.ok(history);
        });
    }

    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<?>> updateChat(@PathVariable String chatId, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            ChatSpec spec = chatManager.get(chatId);
            if (spec == null) {
                return ResponseEntity.notFound().build();
            }
            if (body.containsKey("title")) spec.setTitle(String.valueOf(body.get("title")));
            if (body.containsKey("name")) spec.setTitle(String.valueOf(body.get("name")));
            if (body.containsKey("model")) spec.setModel(String.valueOf(body.get("model")));
            return ResponseEntity.ok(chatManager.update(spec));
        });
    }

    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<?>> deleteChat(@PathVariable String chatId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of(
                "success", chatManager.delete(chatId),
                "deleted_count", 1
        )));
    }

    @PostMapping("/batch-delete")
    public Mono<ResponseEntity<?>> batchDelete(@RequestBody List<String> chatIds) {
        return Mono.fromCallable(() -> {
            int count = 0;
            if (chatIds != null) {
                for (String chatId : chatIds) {
                    if (chatManager.delete(chatId)) count++;
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
}
