package com.melon.app.controller;

import com.melon.app.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for session management.
 * Corresponds to Python session API endpoints.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Lists all sessions for an agent.
     */
    @GetMapping("/{agentId}")
    public Mono<ResponseEntity<?>> listSessions(@PathVariable String agentId) {
        return Mono.fromCallable(() -> {
            try {
                var sessions = sessionService.listSessions(agentId);
                return ResponseEntity.ok(sessions);
            } catch (Exception e) {
                log.error("Failed to list sessions for agent {}", agentId, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * Gets a specific session by ID.
     */
    @GetMapping("/{agentId}/{sessionId}")
    public Mono<ResponseEntity<?>> getSession(@PathVariable String agentId, @PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            try {
                var session = sessionService.getSession(agentId, sessionId);
                if (session == null) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok(session);
            } catch (Exception e) {
                log.error("Failed to get session {}/{}", agentId, sessionId, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * Creates a new session.
     */
    @PostMapping("/{agentId}")
    public Mono<ResponseEntity<?>> createSession(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            try {
                String sessionName = (String) body.getOrDefault("name", "session-" + System.currentTimeMillis());
                var session = sessionService.createSession(agentId, sessionName);
                return ResponseEntity.ok(session);
            } catch (Exception e) {
                log.error("Failed to create session for agent {}", agentId, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * Deletes a session.
     */
    @DeleteMapping("/{agentId}/{sessionId}")
    public Mono<ResponseEntity<?>> deleteSession(@PathVariable String agentId, @PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            try {
                sessionService.deleteSession(agentId, sessionId);
                return ResponseEntity.ok(Map.of("status", "deleted"));
            } catch (Exception e) {
                log.error("Failed to delete session {}/{}", agentId, sessionId, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * Renames a session.
     */
    @PatchMapping("/{agentId}/{sessionId}")
    public Mono<ResponseEntity<?>> renameSession(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                String newName = body.get("name");
                sessionService.renameSession(agentId, sessionId, newName);
                return ResponseEntity.ok(Map.of("status", "renamed", "name", newName));
            } catch (Exception e) {
                log.error("Failed to rename session {}/{}", agentId, sessionId, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }
}
