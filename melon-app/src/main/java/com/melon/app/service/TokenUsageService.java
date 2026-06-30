/**
 * @author melon
 */
package com.melon.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for tracking token usage across sessions and agents.
 * Corresponds to Python TokenRecordingModelWrapper.
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    // sessionId -> usage stats
    private final ConcurrentHashMap<String, UsageStats> sessionUsage = new ConcurrentHashMap<>();
    // agentId -> usage stats
    private final ConcurrentHashMap<String, UsageStats> agentUsage = new ConcurrentHashMap<>();
    // total usage
    private final UsageStats totalUsage = new UsageStats();

    /**
     * Records token usage for a session and agent.
     */
    public void recordUsage(String sessionId, String agentId, long inputTokens, long outputTokens, long totalTokens) {
        sessionUsage.computeIfAbsent(sessionId, k -> new UsageStats())
                    .add(inputTokens, outputTokens, totalTokens);
        agentUsage.computeIfAbsent(agentId, k -> new UsageStats())
                  .add(inputTokens, outputTokens, totalTokens);
        totalUsage.add(inputTokens, outputTokens, totalTokens);
    }

    /**
     * Gets token usage for a session.
     */
    public Map<String, Object> getSessionUsage(String sessionId) {
        UsageStats stats = sessionUsage.get(sessionId);
        if (stats == null) {
            return Map.of("session_id", sessionId, "input_tokens", 0, "output_tokens", 0, "total_tokens", 0);
        }
        return stats.toMap("session_id", sessionId);
    }

    /**
     * Gets token usage for an agent.
     */
    public Map<String, Object> getAgentUsage(String agentId) {
        UsageStats stats = agentUsage.get(agentId);
        if (stats == null) {
            return Map.of("agent_id", agentId, "input_tokens", 0, "output_tokens", 0, "total_tokens", 0);
        }
        return stats.toMap("agent_id", agentId);
    }

    /**
     * Gets total token usage.
     */
    public Map<String, Object> getTotalUsage() {
        return totalUsage.toMap("scope", "total");
    }

    /**
     * Resets token usage for a session.
     */
    public void resetSessionUsage(String sessionId) {
        sessionUsage.remove(sessionId);
        log.info("Token usage reset for session: {}", sessionId);
    }

    /**
     * Inner class for tracking usage statistics.
     */
    static class UsageStats {
        final AtomicLong inputTokens = new AtomicLong();
        final AtomicLong outputTokens = new AtomicLong();
        final AtomicLong totalTokens = new AtomicLong();
        final AtomicLong callCount = new AtomicLong();

        void add(long input, long output, long total) {
            inputTokens.addAndGet(input);
            outputTokens.addAndGet(output);
            totalTokens.addAndGet(total);
            callCount.incrementAndGet();
        }

        Map<String, Object> toMap(String idKey, String idValue) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(idKey, idValue);
            map.put("input_tokens", inputTokens.get());
            map.put("output_tokens", outputTokens.get());
            map.put("total_tokens", totalTokens.get());
            map.put("call_count", callCount.get());
            return map;
        }
    }
}
