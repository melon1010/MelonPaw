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
 * Agent 统计服务. 记录和查询 agent 运行统计.
 * Corresponds to Python agent_stats_service.py.
 */
@Service
public class AgentStatsService {

    private static final Logger log = LoggerFactory.getLogger(AgentStatsService.class);

    // agentId -> StatsEntry
    private final ConcurrentHashMap<String, StatsEntry> statsMap = new ConcurrentHashMap<>();

    /**
     * 记录一次 agent 调用.
     */
    public void recordCall(String agentId, long inputTokens, long outputTokens,
                           long durationMillis, boolean success) {
        statsMap.computeIfAbsent(agentId, k -> new StatsEntry())
                .record(inputTokens, outputTokens, durationMillis, success);
        log.debug("Stats recorded for agent {}: tokens={}+{} duration={}ms success={}",
                agentId, inputTokens, outputTokens, durationMillis, success);
    }

    /**
     * 获取 agent 统计信息.
     */
    public Map<String, Object> getStats(String agentId) {
        StatsEntry entry = statsMap.get(agentId);
        if (entry == null) {
            return defaultStats(agentId);
        }
        return entry.toMap(agentId);
    }

    /**
     * 获取所有 agent 统计信息.
     */
    public List<Map<String, Object>> getAllStats() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : statsMap.entrySet()) {
            result.add(entry.getValue().toMap(entry.getKey()));
        }
        return result;
    }

    /**
     * 重置 agent 统计.
     */
    public void resetStats(String agentId) {
        statsMap.remove(agentId);
        log.info("Stats reset for agent: {}", agentId);
    }

    /**
     * 重置所有统计.
     */
    public void resetAllStats() {
        statsMap.clear();
        log.info("All agent stats reset");
    }

    private Map<String, Object> defaultStats(String agentId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agent_id", agentId);
        map.put("total_calls", 0);
        map.put("successful_calls", 0);
        map.put("failed_calls", 0);
        map.put("success_rate", 0.0);
        map.put("total_input_tokens", 0);
        map.put("total_output_tokens", 0);
        map.put("total_tokens", 0);
        map.put("avg_duration_ms", 0.0);
        map.put("total_duration_ms", 0);
        return map;
    }

    /**
     * 统计数据条目.
     */
    static class StatsEntry {
        final AtomicLong totalCalls = new AtomicLong();
        final AtomicLong successfulCalls = new AtomicLong();
        final AtomicLong failedCalls = new AtomicLong();
        final AtomicLong totalInputTokens = new AtomicLong();
        final AtomicLong totalOutputTokens = new AtomicLong();
        final AtomicLong totalDurationMs = new AtomicLong();

        void record(long inputTokens, long outputTokens, long durationMillis, boolean success) {
            totalCalls.incrementAndGet();
            if (success) {
                successfulCalls.incrementAndGet();
            } else {
                failedCalls.incrementAndGet();
            }
            totalInputTokens.addAndGet(inputTokens);
            totalOutputTokens.addAndGet(outputTokens);
            totalDurationMs.addAndGet(durationMillis);
        }

        Map<String, Object> toMap(String agentId) {
            Map<String, Object> map = new LinkedHashMap<>();
            long calls = totalCalls.get();
            map.put("agent_id", agentId);
            map.put("total_calls", calls);
            map.put("successful_calls", successfulCalls.get());
            map.put("failed_calls", failedCalls.get());
            map.put("success_rate", calls > 0 ? (double) successfulCalls.get() / calls : 0.0);
            map.put("total_input_tokens", totalInputTokens.get());
            map.put("total_output_tokens", totalOutputTokens.get());
            map.put("total_tokens", totalInputTokens.get() + totalOutputTokens.get());
            map.put("avg_duration_ms", calls > 0 ? (double) totalDurationMs.get() / calls : 0.0);
            map.put("total_duration_ms", totalDurationMs.get());
            return map;
        }
    }
}
