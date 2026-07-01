package com.melon.app.controller;

import com.melon.app.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for plan management.
 * Handles plan creation, confirmation, and status queries.
 * Corresponds to Python plan API endpoints.
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    private final ApprovalService approvalService;

    public PlanController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * Gets the current plan for a session.
     */
    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<?>> getPlan(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            var plan = approvalService.getPlan(sessionId);
            if (plan == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(plan);
        });
    }

    /**
     * Confirms a plan, allowing the agent to proceed.
     */
    @PostMapping("/{sessionId}/confirm")
    public Mono<ResponseEntity<?>> confirmPlan(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
            approvalService.confirmPlan(sessionId);
            return ResponseEntity.ok(Map.of("status", "confirmed", "sessionId", sessionId));
        });
    }

    /**
     * Rejects a plan, requiring the agent to revise.
     */
    @PostMapping("/{sessionId}/reject")
    public Mono<ResponseEntity<?>> rejectPlan(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String reason = body != null ? body.get("reason") : null;
            approvalService.rejectPlan(sessionId, reason);
            return ResponseEntity.ok(Map.of("status", "rejected", "sessionId", sessionId));
        });
    }
}
