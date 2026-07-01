package com.melon.tools.util;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 用量查询工具. 对应 Python get_token_usage.py.
 */
public class GetTokenUsageTool {

    /** In-memory token usage store: records keyed by timestamp */
    private static final List<UsageRecord> usageRecords = Collections.synchronizedList(new ArrayList<>());

    /** Optional provider wired by the app layer for real usage data */
    private static TokenUsageProvider tokenUsageProvider;

    /**
     * Functional interface for fetching token usage data (wired by app layer).
     */
    @FunctionalInterface
    public interface TokenUsageProvider {
        Map<String, Object> getUsage(Integer days, String modelName, String providerId);
    }

    /**
     * Sets the token usage provider (wired by the app layer to TokenUsageService).
     */
    public static void setTokenUsageProvider(TokenUsageProvider provider) {
        tokenUsageProvider = provider;
    }

    /**
     * Records a token usage entry (called by the app layer or middleware).
     */
    public static void recordUsage(String sessionId, String agentId, String modelName,
                                   String providerId, long inputTokens, long outputTokens) {
        usageRecords.add(new UsageRecord(System.currentTimeMillis(), sessionId, agentId,
                modelName, providerId, inputTokens, outputTokens));
    }

    @Tool(name = "get_token_usage", description = "Get LLM token usage statistics", readOnly = true)
    public String getTokenUsage(
            @ToolParam(name = "days", description = "Number of days to look back (default 30)") Integer days,
            @ToolParam(name = "model_name", description = "Filter by model name (optional)") String modelName,
            @ToolParam(name = "provider_id", description = "Filter by provider ID (optional)") String providerId
    ) {
        int d = days != null ? days : 30;

        // If a real provider is wired, use it
        if (tokenUsageProvider != null) {
            try {
                Map<String, Object> data = tokenUsageProvider.getUsage(d, modelName, providerId);
                return formatUsageData(data, d, modelName, providerId);
            } catch (Exception e) {
                return "Error fetching token usage: " + e.getMessage();
            }
        }

        // Fallback: query in-memory store
        long cutoffTime = System.currentTimeMillis() - (d * 24L * 60L * 60L * 1000L);

        AtomicLong totalInput = new AtomicLong(0);
        AtomicLong totalOutput = new AtomicLong(0);
        AtomicLong totalTokens = new AtomicLong(0);
        int matchCount = 0;

        // Use a snapshot to avoid concurrent modification
        List<UsageRecord> snapshot;
        synchronized (usageRecords) {
            snapshot = new ArrayList<>(usageRecords);
        }

        Map<String, long[]> byModel = new LinkedHashMap<>();
        Map<String, long[]> byProvider = new LinkedHashMap<>();

        for (UsageRecord record : snapshot) {
            if (record.timestamp < cutoffTime) {
                continue;
            }
            if (modelName != null && !modelName.isBlank()
                    && (record.modelName == null || !record.modelName.contains(modelName))) {
                continue;
            }
            if (providerId != null && !providerId.isBlank()
                    && (record.providerId == null || !record.providerId.contains(providerId))) {
                continue;
            }

            totalInput.addAndGet(record.inputTokens);
            totalOutput.addAndGet(record.outputTokens);
            totalTokens.addAndGet(record.inputTokens + record.outputTokens);
            matchCount++;

            // Aggregate by model
            String modelKey = record.modelName != null ? record.modelName : "(unknown)";
            byModel.computeIfAbsent(modelKey, k -> new long[3]);
            byModel.get(modelKey)[0] += record.inputTokens;
            byModel.get(modelKey)[1] += record.outputTokens;
            byModel.get(modelKey)[2] += record.inputTokens + record.outputTokens;

            // Aggregate by provider
            String providerKey = record.providerId != null ? record.providerId : "(unknown)";
            byProvider.computeIfAbsent(providerKey, k -> new long[3]);
            byProvider.get(providerKey)[0] += record.inputTokens;
            byProvider.get(providerKey)[1] += record.outputTokens;
            byProvider.get(providerKey)[2] += record.inputTokens + record.outputTokens;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Token Usage Report (last ").append(d).append(" days) ===\n\n");

        if (modelName != null && !modelName.isBlank()) {
            sb.append("Filter: model_name=").append(modelName).append("\n");
        }
        if (providerId != null && !providerId.isBlank()) {
            sb.append("Filter: provider_id=").append(providerId).append("\n");
        }
        if (modelName != null || providerId != null) {
            sb.append("\n");
        }

        sb.append("Total:\n");
        sb.append("  Input tokens:  ").append(totalInput.get()).append("\n");
        sb.append("  Output tokens: ").append(totalOutput.get()).append("\n");
        sb.append("  Total tokens:  ").append(totalTokens.get()).append("\n");
        sb.append("  API calls:     ").append(matchCount).append("\n");

        if (!byModel.isEmpty()) {
            sb.append("\nBy Model:\n");
            for (Map.Entry<String, long[]> entry : byModel.entrySet()) {
                long[] vals = entry.getValue();
                sb.append("  ").append(entry.getKey())
                  .append(": input=").append(vals[0])
                  .append(", output=").append(vals[1])
                  .append(", total=").append(vals[2])
                  .append("\n");
            }
        }

        if (!byProvider.isEmpty()) {
            sb.append("\nBy Provider:\n");
            for (Map.Entry<String, long[]> entry : byProvider.entrySet()) {
                long[] vals = entry.getValue();
                sb.append("  ").append(entry.getKey())
                  .append(": input=").append(vals[0])
                  .append(", output=").append(vals[1])
                  .append(", total=").append(vals[2])
                  .append("\n");
            }
        }

        if (matchCount == 0) {
            sb.append("\n(No usage records found matching the criteria)");
        }

        return sb.toString();
    }

    private String formatUsageData(Map<String, Object> data, int days, String modelName, String providerId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Token Usage Report (last ").append(days).append(" days) ===\n\n");

        if (modelName != null && !modelName.isBlank()) {
            sb.append("Filter: model_name=").append(modelName).append("\n");
        }
        if (providerId != null && !providerId.isBlank()) {
            sb.append("Filter: provider_id=").append(providerId).append("\n");
        }
        if (modelName != null || providerId != null) {
            sb.append("\n");
        }

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Inner class representing a single usage record.
     */
    private static class UsageRecord {
        final long timestamp;
        final String sessionId;
        final String agentId;
        final String modelName;
        final String providerId;
        final long inputTokens;
        final long outputTokens;

        UsageRecord(long timestamp, String sessionId, String agentId,
                    String modelName, String providerId,
                    long inputTokens, long outputTokens) {
            this.timestamp = timestamp;
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.modelName = modelName;
            this.providerId = providerId;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }
}
