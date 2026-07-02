package com.melon.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Service for tracking token usage across sessions and agents.
 * Corresponds to Python TokenRecordingModelWrapper.
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // sessionId -> usage stats
    private final ConcurrentHashMap<String, UsageStats> sessionUsage = new ConcurrentHashMap<>();
    // agentId -> usage stats
    private final ConcurrentHashMap<String, UsageStats> agentUsage = new ConcurrentHashMap<>();
    // date|provider|model -> usage stats
    private final ConcurrentHashMap<String, UsageStats> detailUsage = new ConcurrentHashMap<>();
    // total usage
    private final UsageStats totalUsage = new UsageStats();
    private final ConfigManager configManager;

    public TokenUsageService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Records token usage for a session and agent.
     */
    public void recordUsage(String sessionId, String agentId, long inputTokens, long outputTokens, long totalTokens) {
        recordUsage(sessionId, agentId, "", inputTokens, outputTokens, totalTokens);
    }

    public void recordUsage(String sessionId, String agentId, String modelId, long inputTokens, long outputTokens, long totalTokens) {
        String safeSessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        String safeAgentId = agentId == null || agentId.isBlank() ? "default" : agentId;
        ModelRef model = parseModel(modelId);
        sessionUsage.computeIfAbsent(safeSessionId, k -> new UsageStats())
                    .add(inputTokens, outputTokens, totalTokens);
        agentUsage.computeIfAbsent(safeAgentId, k -> new UsageStats())
                  .add(inputTokens, outputTokens, totalTokens);
        detailUsage.computeIfAbsent(detailKey(LocalDate.now().toString(), model.providerId(), model.model()), k -> new UsageStats())
                   .add(inputTokens, outputTokens, totalTokens);
        totalUsage.add(inputTokens, outputTokens, totalTokens);
        log.debug("Token usage recorded: agent={}, session={}, model={}, input={}, output={}, total={}",
                safeAgentId, safeSessionId, modelId, inputTokens, outputTokens, totalTokens);
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

    public List<Map<String, Object>> getDetails(String startDate, String endDate, String providerId, String modelName) {
        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now());
        Map<String, UsageStats> details = new LinkedHashMap<>(loadPersistedDetails());
        detailUsage.forEach(details::putIfAbsent);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map.Entry<String, UsageStats> entry : details.entrySet()) {
            DetailKey key = parseDetailKey(entry.getKey());
            if (key == null) continue;
            LocalDate date = parseDate(key.date(), null);
            if (date == null || date.isBefore(start) || date.isAfter(end)) continue;
            if (providerId != null && !providerId.isBlank() && !providerId.equals(key.providerId())) continue;
            if (modelName != null && !modelName.isBlank() && !modelName.equals(key.model())) continue;
            UsageStats stats = entry.getValue();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("date", key.date());
            record.put("provider_id", key.providerId());
            record.put("model", key.model());
            record.put("prompt_tokens", stats.inputTokens.get());
            record.put("completion_tokens", stats.outputTokens.get());
            record.put("call_count", stats.callCount.get());
            records.add(record);
        }
        records.sort(Comparator
                .comparing((Map<String, Object> r) -> String.valueOf(r.get("date")))
                .thenComparing(r -> String.valueOf(r.get("provider_id")))
                .thenComparing(r -> String.valueOf(r.get("model"))));
        return records;
    }

    public Map<String, Object> getSummary(String startDate, String endDate, String providerId, String modelName) {
        List<Map<String, Object>> details = getDetails(startDate, endDate, providerId, modelName);
        Map<String, UsageStats> byModelStats = new LinkedHashMap<>();
        Map<String, UsageStats> byDateStats = new LinkedHashMap<>();
        long prompt = 0;
        long completion = 0;
        long calls = 0;
        for (Map<String, Object> row : details) {
            long pt = numberValue(row.get("prompt_tokens"));
            long ct = numberValue(row.get("completion_tokens"));
            long cc = numberValue(row.get("call_count"));
            prompt += pt;
            completion += ct;
            calls += cc;

            String provider = String.valueOf(row.get("provider_id"));
            String model = String.valueOf(row.get("model"));
            UsageStats byModel = byModelStats.computeIfAbsent(provider + ":" + model, k -> new UsageStats());
            byModel.add(pt, ct, pt + ct, cc);
            byDateStats.computeIfAbsent(String.valueOf(row.get("date")), k -> new UsageStats())
                    .add(pt, ct, pt + ct, cc);
        }

        Map<String, Object> byModel = new LinkedHashMap<>();
        byModelStats.forEach((key, stats) -> {
            ModelRef ref = parseModel(key);
            Map<String, Object> value = stats.toFrontendMap();
            value.put("provider_id", ref.providerId());
            value.put("model", ref.model());
            byModel.put(key, value);
        });
        Map<String, Object> byDate = new LinkedHashMap<>();
        byDateStats.forEach((key, stats) -> byDate.put(key, stats.toFrontendMap()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_prompt_tokens", prompt);
        result.put("total_completion_tokens", completion);
        result.put("total_calls", calls);
        result.put("by_model", byModel);
        result.put("by_date", byDate);
        return result;
    }

    private Map<String, UsageStats> loadPersistedDetails() {
        Map<String, UsageStats> result = new LinkedHashMap<>();
        for (WorkspaceRef workspace : workspaceRefs()) {
            Path sessionsDir = workspace.workspaceDir().resolve("sessions");
            if (!Files.isDirectory(sessionsDir)) continue;
            try (var stream = Files.find(sessionsDir, 4,
                    (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().endsWith(".json"))) {
                stream.sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> collectSessionUsage(path, workspace, result));
            } catch (IOException e) {
                log.debug("Failed to scan token usage sessions under {}", sessionsDir, e);
            }
        }
        return result;
    }

    private void collectSessionUsage(Path sessionFile, WorkspaceRef workspace, Map<String, UsageStats> result) {
        Map<String, Object> raw = JsonUtils.load(sessionFile, MAP_TYPE);
        if (raw == null || raw.isEmpty()) return;
        Object agentRaw = raw.get("agent");
        if (!(agentRaw instanceof Map<?, ?> agentMap)) return;
        Object stateRaw = asMap(agentMap).get("state");
        if (!(stateRaw instanceof Map<?, ?> stateMap)) return;
        Object contextRaw = asMap(stateMap).get("context");
        if (!(contextRaw instanceof List<?> context)) return;

        ModelRef model = parseModel(workspace.modelId());
        for (Object item : context) {
            if (!(item instanceof Map<?, ?> messageRaw)) continue;
            Map<String, Object> message = asMap(messageRaw);
            if (!"assistant".equalsIgnoreCase(stringValue(message.get("role")))) continue;
            UsageNumbers usage = extractUsage(message);
            if (usage == null || usage.totalTokens() <= 0) continue;
            String date = messageDate(message, sessionFile);
            result.computeIfAbsent(detailKey(date, model.providerId(), model.model()), k -> new UsageStats())
                    .add(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
        }
    }

    private UsageNumbers extractUsage(Map<String, Object> message) {
        Object metadataRaw = message.get("metadata");
        if (metadataRaw instanceof Map<?, ?> metadataMap) {
            Map<String, Object> metadata = asMap(metadataMap);
            Object turnRaw = metadata.get("qwenpaw_turn_usage");
            if (turnRaw instanceof Map<?, ?> turnMap) {
                Object usageRaw = asMap(turnMap).get("usage");
                UsageNumbers usage = usageFromMap(usageRaw);
                if (usage != null) return usage;
            }
            UsageNumbers chatUsage = usageFromMap(metadata.get("_chat_usage"));
            if (chatUsage != null) return chatUsage;
        }
        return usageFromMap(message.get("usage"));
    }

    private UsageNumbers usageFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> mapRaw)) return null;
        Map<String, Object> map = asMap(mapRaw);
        long prompt = firstNumber(map.get("prompt_tokens"), map.get("input_tokens"), map.get("inputTokens"));
        long completion = firstNumber(map.get("completion_tokens"), map.get("output_tokens"), map.get("outputTokens"));
        long total = firstNumber(map.get("total_tokens"), map.get("totalTokens"));
        if (total == 0) total = prompt + completion;
        if (prompt == 0 && total > completion) prompt = total - completion;
        if (completion == 0 && total > prompt) completion = total - prompt;
        return total > 0 ? new UsageNumbers(prompt, completion, total) : null;
    }

    private String messageDate(Map<String, Object> message, Path fallbackFile) {
        String timestamp = stringValue(message.get("timestamp"));
        if (timestamp.length() >= 10) {
            String prefix = timestamp.substring(0, 10);
            try {
                return LocalDate.parse(prefix).toString();
            } catch (Exception ignored) {
                // Fall back to file mtime below.
            }
        }
        try {
            return Instant.ofEpochMilli(Files.getLastModifiedTime(fallbackFile).toMillis())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString();
        } catch (IOException e) {
            return LocalDate.now().toString();
        }
    }

    private List<WorkspaceRef> workspaceRefs() {
        Map<String, Path> refs = new LinkedHashMap<>();
        if (configManager.getConfig() != null && configManager.getConfig().getAgents() != null) {
            for (String agentId : configManager.getConfig().getAgents().keySet()) {
                refs.put(agentId, configManager.resolveWorkspaceDir(agentId));
            }
        }
        Path root = configManager.resolveWorkspaceRootDir();
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory)
                        .forEach(path -> refs.putIfAbsent(path.getFileName().toString(), path));
            } catch (IOException e) {
                log.debug("Failed to list workspace root {}", root, e);
            }
        }
        List<WorkspaceRef> result = new ArrayList<>();
        refs.forEach((agentId, dir) -> result.add(new WorkspaceRef(agentId, dir, activeModel(agentId, dir))));
        return result;
    }

    private String activeModel(String agentId, Path workspaceDir) {
        if (configManager.getConfig() != null && configManager.getConfig().getAgents() != null) {
            AgentConfig agent = configManager.getConfig().getAgents().get(agentId);
            if (agent != null && agent.getActiveModel() != null && !agent.getActiveModel().isBlank()) {
                return agent.getActiveModel();
            }
        }
        Map<String, Object> agentJson = JsonUtils.loadAsMap(workspaceDir.resolve("agent.json"));
        String activeModel = stringValue(agentJson.get("active_model"));
        if (!activeModel.isBlank()) return activeModel;
        Object configRaw = agentJson.get("config");
        if (configRaw instanceof Map<?, ?> configMap) {
            activeModel = stringValue(asMap(configMap).get("active_model"));
            if (!activeModel.isBlank()) return activeModel;
        }
        return "unknown:unknown";
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
            add(input, output, total, 1);
        }

        void add(long input, long output, long total, long calls) {
            inputTokens.addAndGet(input);
            outputTokens.addAndGet(output);
            totalTokens.addAndGet(total);
            callCount.addAndGet(calls);
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

        Map<String, Object> toFrontendMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("prompt_tokens", inputTokens.get());
            map.put("completion_tokens", outputTokens.get());
            map.put("call_count", callCount.get());
            return map;
        }
    }

    private ModelRef parseModel(String modelId) {
        String value = modelId == null || modelId.isBlank() ? "unknown:unknown" : modelId;
        int idx = value.indexOf(':');
        if (idx > 0 && idx < value.length() - 1) {
            return new ModelRef(value.substring(0, idx), value.substring(idx + 1));
        }
        return new ModelRef("unknown", value);
    }

    private String detailKey(String date, String providerId, String model) {
        return date + "|" + providerId + "|" + model;
    }

    private DetailKey parseDetailKey(String raw) {
        String[] parts = raw.split("\\|", 3);
        return parts.length == 3 ? new DetailKey(parts[0], parts[1], parts[2]) : null;
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private long numberValue(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private long firstNumber(Object... values) {
        for (Object value : values) {
            if (value instanceof Number n) return n.longValue();
        }
        return 0L;
    }

    private Map<String, Object> asMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private record ModelRef(String providerId, String model) {}

    private record DetailKey(String date, String providerId, String model) {}

    private record UsageNumbers(long promptTokens, long completionTokens, long totalTokens) {}

    private record WorkspaceRef(String agentId, Path workspaceDir, String modelId) {}
}
