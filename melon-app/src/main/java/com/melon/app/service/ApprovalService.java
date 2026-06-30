/**
 * @author melon
 */
package com.melon.app.service;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing tool approvals and plans.
 * Handles approval mode queries, pending approvals, and plan confirmations.
 * Corresponds to Python ToolGuard and PlanGate approval system.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ConfigManager configManager;

    // sessionId -> pending approval request data
    private final ConcurrentHashMap<String, Map<String, Object>> pendingApprovals = new ConcurrentHashMap<>();
    // sessionId -> pending plan data
    private final ConcurrentHashMap<String, Map<String, Object>> pendingPlans = new ConcurrentHashMap<>();
    // sessionId -> pending approval future (true=approved, false=denied)
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovalFutures = new ConcurrentHashMap<>();
    // sessionId -> pending plan future (true=confirmed, false=rejected)
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingPlanFutures = new ConcurrentHashMap<>();

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
        return pendingApprovals.get(sessionId);
    }

    /**
     * Sets a pending approval request for a session.
     * Also creates a CompletableFuture that the agent thread can wait on.
     */
    public void setPendingApproval(String sessionId, Map<String, Object> approvalRequest) {
        pendingApprovals.put(sessionId, approvalRequest);
        pendingApprovalFutures.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    /**
     * Returns the CompletableFuture for a pending approval.
     * The agent thread can call .get() or .join() on this to block until approval/denial.
     */
    public CompletableFuture<Boolean> waitForApproval(String sessionId) {
        return pendingApprovalFutures.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    /**
     * Approves a pending tool call.
     * Completes the pending future with true to unblock the agent thread.
     */
    public void approve(String sessionId, String modifiedCommand) {
        pendingApprovals.remove(sessionId);
        CompletableFuture<Boolean> future = pendingApprovalFutures.remove(sessionId);
        if (future != null) {
            future.complete(true);
        }
        log.info("Approval granted for session: {}", sessionId);
    }

    /**
     * Denies a pending tool call.
     * Completes the pending future with false to unblock the agent thread.
     */
    public void deny(String sessionId, String reason) {
        pendingApprovals.remove(sessionId);
        CompletableFuture<Boolean> future = pendingApprovalFutures.remove(sessionId);
        if (future != null) {
            future.complete(false);
        }
        log.info("Approval denied for session: {} (reason: {})", sessionId, reason);
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
        pendingPlanFutures.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    /**
     * Returns the CompletableFuture for a pending plan confirmation.
     * The agent thread can call .get() or .join() on this to block until confirm/reject.
     */
    public CompletableFuture<Boolean> waitForPlanConfirmation(String sessionId) {
        return pendingPlanFutures.computeIfAbsent(sessionId, k -> new CompletableFuture<>());
    }

    /**
     * Confirms a plan.
     * Completes the pending future with true to unblock the agent thread.
     */
    public void confirmPlan(String sessionId) {
        pendingPlans.remove(sessionId);
        CompletableFuture<Boolean> future = pendingPlanFutures.remove(sessionId);
        if (future != null) {
            future.complete(true);
        }
        log.info("Plan confirmed for session: {}", sessionId);
    }

    /**
     * Rejects a plan.
     * Completes the pending future with false to signal the agent to revise.
     */
    public void rejectPlan(String sessionId, String reason) {
        pendingPlans.remove(sessionId);
        CompletableFuture<Boolean> future = pendingPlanFutures.remove(sessionId);
        if (future != null) {
            future.complete(false);
        }
        log.info("Plan rejected for session: {} (reason: {})", sessionId, reason);
    }
}
