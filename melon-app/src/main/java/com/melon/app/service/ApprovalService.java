package com.melon.app.service;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * Service for managing tool approvals and plans.
 * Handles approval mode queries, pending approvals, and plan confirmations.
 * Corresponds to Python ToolGuard and PlanGate approval system.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ConfigManager configManager;

    // sessionId -> live approval gate for the paused AgentScope stream
    private final ConcurrentHashMap<String, PendingApprovalSession> pendingApprovalSessions = new ConcurrentHashMap<>();
    // sessionId -> pending plan data
    private final ConcurrentHashMap<String, Map<String, Object>> pendingPlans = new ConcurrentHashMap<>();

    public ApprovalService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Gets the approval mode for an agent.
     */
    public String getApprovalMode(String agentId) {
        var agentConfig = configManager.getConfig().getAgents().get(agentId);
        if (agentConfig == null || agentConfig.getApproval() == null) {
            return "AUTO";
        }
        return agentConfig.getApproval().getLevel();
    }

    /**
     * Sets the approval mode for an agent.
     */
    public void setApprovalMode(String agentId, String mode) {
        var config = configManager.getConfig();
        var agentConfig = config.getAgents().get(agentId);
        if (agentConfig == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        if (agentConfig.getApproval() == null) {
            agentConfig.setApproval(new AgentConfig.ApprovalConfig());
        }
        agentConfig.getApproval().setLevel(mode);
        configManager.save();
        log.info("Approval mode set to {} for agent {}", mode, agentId);
    }

    /**
     * Gets the pending approval for a session.
     */
    public Map<String, Object> getPendingApproval(String sessionId) {
        PendingApprovalSession session = pendingApprovalSessions.get(sessionId);
        return session != null ? session.firstApproval() : null;
    }

    public List<Map<String, Object>> getPendingApprovals() {
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (PendingApprovalSession session : pendingApprovalSessions.values()) {
            approvals.addAll(session.approvals());
        }
        return approvals;
    }

    public Mono<List<ConfirmResult>> openPendingApproval(String sessionId, List<ToolUseBlock> toolCalls,
                                                         List<Map<String, Object>> approvalRequests) {
        PendingApprovalSession session = new PendingApprovalSession(toolCalls, approvalRequests);
        PendingApprovalSession previous = pendingApprovalSessions.put(sessionId, session);
        if (previous != null) {
            previous.cancel();
        }
        return Mono.fromFuture(session.future());
    }

    public boolean decidePendingApproval(String sessionId, String requestId, boolean approved) {
        PendingApprovalSession session = pendingApprovalSessions.get(sessionId);
        if (session == null) return false;
        boolean accepted = session.decide(requestId, approved);
        if (session.isDone()) {
            pendingApprovalSessions.remove(sessionId, session);
        }
        return accepted;
    }

    /**
     * Gets the current plan for a session.
     */
    public Map<String, Object> getPlan(String sessionId) {
        return pendingPlans.get(sessionId);
    }

    /**
     * Sets a plan for a session.
     * Also creates a CompletableFuture that the agent thread can wait on.
     */
    public void setPlan(String sessionId, Map<String, Object> plan) {
        pendingPlans.put(sessionId, plan);
    }

    /**
     * Confirms a plan.
     * Completes the pending future with true to unblock the agent thread.
     */
    public void confirmPlan(String sessionId) {
        pendingPlans.remove(sessionId);
        log.info("Plan confirmed for session: {}", sessionId);
    }

    /**
     * Rejects a plan.
     * Completes the pending future with false to signal the agent to revise.
     */
    public void rejectPlan(String sessionId, String reason) {
        pendingPlans.remove(sessionId);
        log.info("Plan rejected for session: {} (reason: {})", sessionId, reason);
    }

    private static final class PendingApprovalSession {
        private final Map<String, ToolUseBlock> toolCalls = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> approvals = new LinkedHashMap<>();
        private final List<ConfirmResult> results = new ArrayList<>();
        private final CompletableFuture<List<ConfirmResult>> future = new CompletableFuture<>();

        PendingApprovalSession(List<ToolUseBlock> toolCalls, List<Map<String, Object>> approvalRequests) {
            if (toolCalls != null) {
                for (ToolUseBlock toolCall : toolCalls) {
                    if (toolCall != null && toolCall.getId() != null) {
                        this.toolCalls.put(toolCall.getId(), toolCall);
                    }
                }
            }
            if (approvalRequests != null) {
                for (Map<String, Object> request : approvalRequests) {
                    if (request == null) continue;
                    String id = String.valueOf(request.getOrDefault("request_id", ""));
                    if (!id.isBlank()) {
                        this.approvals.put(id, request);
                    }
                }
            }
        }

        synchronized boolean decide(String requestId, boolean approved) {
            if (future.isDone()) return false;
            String id = requestId != null && !requestId.isBlank() ? requestId : firstPendingId();
            ToolUseBlock toolCall = toolCalls.remove(id);
            approvals.remove(id);
            if (toolCall == null) return false;
            results.add(new ConfirmResult(approved, toolCall));
            if (toolCalls.isEmpty()) {
                future.complete(List.copyOf(results));
            }
            return true;
        }

        synchronized Map<String, Object> firstApproval() {
            return approvals.values().stream().findFirst().orElse(null);
        }

        synchronized List<Map<String, Object>> approvals() {
            return new ArrayList<>(approvals.values());
        }

        synchronized boolean isDone() {
            return future.isDone();
        }

        CompletableFuture<List<ConfirmResult>> future() {
            return future;
        }

        synchronized void cancel() {
            if (!future.isDone()) {
                List<ConfirmResult> denied = toolCalls.values().stream()
                        .map(toolCall -> new ConfirmResult(false, toolCall))
                        .toList();
                future.complete(denied);
            }
        }

        private String firstPendingId() {
            return toolCalls.keySet().stream().findFirst().orElse("");
        }
    }
}
