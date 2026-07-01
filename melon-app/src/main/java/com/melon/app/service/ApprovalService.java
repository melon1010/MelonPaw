package com.melon.app.service;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
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
    private final ConcurrentHashMap<String, ToolUseBlock> pendingToolCalls = new ConcurrentHashMap<>();
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
        return pendingApprovals.get(sessionId);
    }

    public List<Map<String, Object>> getPendingApprovals() {
        return new ArrayList<>(pendingApprovals.values());
    }

    public void setPendingApproval(String sessionId, ToolUseBlock toolCall, Map<String, Object> approvalRequest) {
        pendingApprovals.put(sessionId, approvalRequest);
        pendingToolCalls.put(sessionId, toolCall);
    }

    public ToolUseBlock removePendingToolCall(String sessionId) {
        pendingApprovals.remove(sessionId);
        return pendingToolCalls.remove(sessionId);
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
}
