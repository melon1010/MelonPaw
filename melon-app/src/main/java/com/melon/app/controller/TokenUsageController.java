package com.melon.app.controller;

import com.melon.app.service.TokenUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for token usage tracking.
 * Corresponds to Python token usage API endpoints.
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    public TokenUsageController(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * Gets token usage for a specific session.
     */
    @GetMapping("/session/{sessionId}")
    public Mono<ResponseEntity<?>> getSessionUsage(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            var usage = tokenUsageService.getSessionUsage(sessionId);
            return ResponseEntity.ok(usage);
        });
    }

    /**
     * Gets token usage for an agent (all sessions).
     */
    @GetMapping("/agent/{agentId}")
    public Mono<ResponseEntity<?>> getAgentUsage(@PathVariable String agentId) {
        return Mono.fromCallable(() -> {
            var usage = tokenUsageService.getAgentUsage(agentId);
            return ResponseEntity.ok(usage);
        });
    }

    /**
     * Gets total token usage across all agents.
     */
    @GetMapping("/total")
    public Mono<ResponseEntity<?>> getTotalUsage() {
        return Mono.fromCallable(() -> {
            var usage = tokenUsageService.getTotalUsage();
            return ResponseEntity.ok(usage);
        });
    }

    /**
     * Resets token usage for a session.
     */
    @PostMapping("/reset/{sessionId}")
    public Mono<ResponseEntity<?>> resetSessionUsage(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            tokenUsageService.resetSessionUsage(sessionId);
            return ResponseEntity.ok(Map.of("status", "reset", "sessionId", sessionId));
        });
    }
}
