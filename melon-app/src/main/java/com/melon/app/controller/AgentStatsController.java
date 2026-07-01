package com.melon.app.controller;

import com.melon.app.service.AgentStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Agent 统计 API. 对应 Python /api/agents/{id}/stats.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentStatsController {

    private static final Logger log = LoggerFactory.getLogger(AgentStatsController.class);

    private final AgentStatsService agentStatsService;

    public AgentStatsController(AgentStatsService agentStatsService) {
        this.agentStatsService = agentStatsService;
    }

    /**
     * 获取指定 agent 的运行统计.
     */
    @GetMapping("/{id}/stats")
    public Mono<ResponseEntity<?>> getStats(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            try {
                Map<String, Object> stats = agentStatsService.getStats(id);
                return ResponseEntity.ok(stats);
            } catch (Exception e) {
                log.error("Failed to get stats for agent: {}", id, e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 获取所有 agent 的统计信息.
     */
    @GetMapping("/stats/all")
    public Mono<ResponseEntity<?>> getAllStats() {
        return Mono.fromCallable(() -> {
            try {
                var stats = agentStatsService.getAllStats();
                return ResponseEntity.ok(stats);
            } catch (Exception e) {
                log.error("Failed to get all agent stats", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 重置指定 agent 的统计.
     */
    @DeleteMapping("/{id}/stats")
    public Mono<ResponseEntity<?>> resetStats(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            agentStatsService.resetStats(id);
            return ResponseEntity.ok(Map.of("status", "reset", "agent_id", id));
        });
    }

    /**
     * 重置所有 agent 的统计.
     */
    @DeleteMapping("/stats/all")
    public Mono<ResponseEntity<?>> resetAllStats() {
        return Mono.fromCallable(() -> {
            agentStatsService.resetAllStats();
            return ResponseEntity.ok(Map.of("status", "all_reset"));
        });
    }
}
