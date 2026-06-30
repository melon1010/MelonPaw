/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for tool approval management.
 * Handles pending approval queries and approval responses.
 * Corresponds to Python ToolGuard approval API endpoints.
 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * Gets the pending approval for a session.
     */
    @GetMapping("/pending/{sessionId}")
    public Mono<ResponseEntity<?>> getPending(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            var pending = approvalService.getPendingApproval(sessionId);
            if (pending == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(pending);
        });
    }

    /**
     * Approves a pending tool call.
     */
    @PostMapping("/{sessionId}/approve")
    public Mono<ResponseEntity<?>> approve(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String modifiedCommand = body != null ? (String) body.get("modified_command") : null;
            approvalService.approve(sessionId, modifiedCommand);
            return ResponseEntity.ok(Map.of("status", "approved", "sessionId", sessionId));
        });
    }

    /**
     * Denies a pending tool call.
     */
    @PostMapping("/{sessionId}/deny")
    public Mono<ResponseEntity<?>> deny(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String reason = body != null ? body.get("reason") : null;
            approvalService.deny(sessionId, reason);
            return ResponseEntity.ok(Map.of("status", "denied", "sessionId", sessionId));
        });
    }

    /**
     * Gets the current approval mode for an agent.
     */
    @GetMapping("/mode/{agentId}")
    public Mono<ResponseEntity<?>> getApprovalMode(@PathVariable String agentId) {
        return Mono.fromCallable(() -> {
            String mode = approvalService.getApprovalMode(agentId);
            return ResponseEntity.ok(Map.of("mode", mode, "agentId", agentId));
        });
    }

    /**
     * Sets the approval mode for an agent.
     */
    @PutMapping("/mode/{agentId}")
    public Mono<ResponseEntity<?>> setApprovalMode(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String mode = body.get("mode");
            approvalService.setApprovalMode(agentId, mode);
            return ResponseEntity.ok(Map.of("status", "updated", "mode", mode, "agentId", agentId));
        });
    }
}
