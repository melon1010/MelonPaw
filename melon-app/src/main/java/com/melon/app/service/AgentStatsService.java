package com.melon.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Workspace-backed statistics for the copied melonPaw Agent Stats page.
 */
@Service
public class AgentStatsService {

    private static final Logger log = LoggerFactory.getLogger(AgentStatsService.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ConfigManager configManager;

    public AgentStatsService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Map<String, Object> getSummary(String agentId, String startDate, String endDate) {
        LocalDate end = parseDate(endDate, LocalDate.now());
        LocalDate start = parseDate(startDate, end.minusDays(30));
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        Map<String, Map<String, Object>> byDate = initDailyStats(start, end);
        Map<String, Map<String, Object>> channelStats = new LinkedHashMap<>();
        Map<String, Set<String>> activeSessions = new LinkedHashMap<>();
        int[] totalToolCalls = {0};
        int[] totalActiveSessions = {0};
        LocalDate rangeStart = start;
        LocalDate rangeEnd = end;

        Path workspace = configManager.resolveWorkspaceDir(agentId);
        countChats(workspace.resolve("chats.json"), byDate, rangeStart, rangeEnd);
        Path sessionsDir = workspace.resolve("sessions");
        if (Files.isDirectory(sessionsDir)) {
            try (var stream = Files.find(sessionsDir, 3,
                    (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().endsWith(".json"))) {
                stream.sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> {
                            SessionStats stats = processSession(path, byDate, channelStats, activeSessions, rangeStart, rangeEnd);
                            totalToolCalls[0] += stats.toolCalls();
                            if (stats.hasMessages()) totalActiveSessions[0]++;
                        });
            } catch (IOException e) {
                log.warn("Failed to scan session stats under {}", sessionsDir, e);
            }
        }

        for (Map.Entry<String, Set<String>> entry : activeSessions.entrySet()) {
            Map<String, Object> row = byDate.get(entry.getKey());
            if (row != null) row.put("active_sessions", entry.getValue().size());
        }

        List<Map<String, Object>> dates = new ArrayList<>(byDate.values());
        long totalUserMessages = sum(dates, "user_messages");
        long totalAssistantMessages = sum(dates, "assistant_messages");
        long totalPromptTokens = sum(dates, "prompt_tokens");
        long totalCompletionTokens = sum(dates, "completion_tokens");
        long totalLlmCalls = sum(dates, "llm_calls");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_active_sessions", totalActiveSessions[0]);
        result.put("total_messages", totalUserMessages + totalAssistantMessages);
        result.put("total_user_messages", totalUserMessages);
        result.put("total_assistant_messages", totalAssistantMessages);
        result.put("total_prompt_tokens", totalPromptTokens);
        result.put("total_completion_tokens", totalCompletionTokens);
        result.put("total_llm_calls", totalLlmCalls);
        result.put("total_tool_calls", totalToolCalls[0]);
        result.put("by_date", dates);
        result.put("channel_stats", new ArrayList<>(channelStats.values()));
        result.put("start_date", start.toString());
        result.put("end_date", end.toString());
        return result;
    }

    private Map<String, Map<String, Object>> initDailyStats(LocalDate start, LocalDate end) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            String date = day.toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("chats", 0);
            row.put("active_sessions", 0);
            row.put("user_messages", 0);
            row.put("assistant_messages", 0);
            row.put("total_messages", 0);
            row.put("prompt_tokens", 0L);
            row.put("completion_tokens", 0L);
            row.put("llm_calls", 0);
            row.put("tool_calls", 0);
            result.put(date, row);
        }
        return result;
    }

    private void countChats(Path chatsFile, Map<String, Map<String, Object>> byDate, LocalDate start, LocalDate end) {
        Map<String, Object> data = JsonUtils.load(chatsFile, MAP_TYPE);
        if (data == null || !(data.get("chats") instanceof List<?> chats)) return;
        for (Object item : chats) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            String createdAt = stringValue(raw.get("created_at"));
            if (createdAt.length() < 10) continue;
            LocalDate date = parseDate(createdAt.substring(0, 10), null);
            if (date == null || date.isBefore(start) || date.isAfter(end)) continue;
            increment(byDate.get(date.toString()), "chats", 1);
        }
    }

    private SessionStats processSession(Path sessionFile,
                                        Map<String, Map<String, Object>> byDate,
                                        Map<String, Map<String, Object>> channelStats,
                                        Map<String, Set<String>> activeSessions,
                                        LocalDate start,
                                        LocalDate end) {
        Map<String, Object> raw = JsonUtils.load(sessionFile, MAP_TYPE);
        if (raw == null || raw.isEmpty()) return new SessionStats(0, false);
        Object contextRaw = context(raw);
        if (!(contextRaw instanceof List<?> context)) return new SessionStats(0, false);

        String channel = sessionFile.getParent() != null ? sessionFile.getParent().getFileName().toString() : "console";
        String sessionStem = sessionFile.getFileName().toString().replaceFirst("\\.json$", "");
        int toolCalls = 0;
        boolean hasMessages = false;
        for (Object item : context) {
            if (!(item instanceof Map<?, ?> messageRaw)) continue;
            Map<String, Object> message = toStringMap(messageRaw);
            String date = messageDate(message, sessionFile);
            LocalDate day = parseDate(date, null);
            if (day == null || day.isBefore(start) || day.isAfter(end)) continue;
            Map<String, Object> daily = byDate.get(date);
            if (daily == null) continue;
            Map<String, Object> channelRow = channelStats.computeIfAbsent(channel, this::newChannelStats);
            hasMessages = true;
            activeSessions.computeIfAbsent(date, k -> new LinkedHashSet<>()).add(sessionStem);

            String role = stringValue(message.get("role")).toLowerCase();
            if ("user".equals(role)) {
                increment(daily, "user_messages", 1);
                increment(daily, "total_messages", 1);
                increment(channelRow, "user_messages", 1);
                increment(channelRow, "total_messages", 1);
            } else if ("assistant".equals(role)) {
                increment(daily, "assistant_messages", 1);
                increment(daily, "total_messages", 1);
                increment(channelRow, "assistant_messages", 1);
                increment(channelRow, "total_messages", 1);
                Usage usage = extractUsage(message);
                if (usage.totalTokens() > 0) {
                    increment(daily, "prompt_tokens", usage.promptTokens());
                    increment(daily, "completion_tokens", usage.completionTokens());
                    increment(daily, "llm_calls", 1);
                }
            }
            int messageToolCalls = countToolCalls(message.get("content"));
            if (messageToolCalls > 0) {
                increment(daily, "tool_calls", messageToolCalls);
                toolCalls += messageToolCalls;
            }
        }
        if (hasMessages) {
            increment(channelStats.computeIfAbsent(channel, this::newChannelStats), "session_count", 1);
        }
        return new SessionStats(toolCalls, hasMessages);
    }

    private Object context(Map<String, Object> session) {
        Object agentRaw = session.get("agent");
        if (!(agentRaw instanceof Map<?, ?> agentMap)) return null;
        Object stateRaw = toStringMap(agentMap).get("state");
        if (!(stateRaw instanceof Map<?, ?> stateMap)) return null;
        return toStringMap(stateMap).get("context");
    }

    private int countToolCalls(Object content) {
        if (!(content instanceof List<?> blocks)) return 0;
        int count = 0;
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> raw)) continue;
            String type = stringValue(raw.get("type"));
            if ("tool_call".equals(type) || "tool_use".equals(type)) count++;
        }
        return count;
    }

    private Usage extractUsage(Map<String, Object> message) {
        Object metadataRaw = message.get("metadata");
        if (metadataRaw instanceof Map<?, ?> metadataMap) {
            Map<String, Object> metadata = toStringMap(metadataMap);
            Object turnRaw = metadata.get("qwenpaw_turn_usage");
            if (turnRaw instanceof Map<?, ?> turnMap) {
                Usage usage = usageFromMap(toStringMap(turnMap).get("usage"));
                if (usage.totalTokens() > 0) return usage;
            }
            Usage chatUsage = usageFromMap(metadata.get("_chat_usage"));
            if (chatUsage.totalTokens() > 0) return chatUsage;
        }
        return usageFromMap(message.get("usage"));
    }

    private Usage usageFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> mapRaw)) return new Usage(0, 0, 0);
        Map<String, Object> map = toStringMap(mapRaw);
        long prompt = firstNumber(map.get("prompt_tokens"), map.get("input_tokens"), map.get("inputTokens"));
        long completion = firstNumber(map.get("completion_tokens"), map.get("output_tokens"), map.get("outputTokens"));
        long total = firstNumber(map.get("total_tokens"), map.get("totalTokens"));
        if (total == 0) total = prompt + completion;
        if (prompt == 0 && total > completion) prompt = total - completion;
        if (completion == 0 && total > prompt) completion = total - prompt;
        return new Usage(prompt, completion, total);
    }

    private Map<String, Object> newChannelStats(String channel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", channel);
        row.put("session_count", 0);
        row.put("user_messages", 0);
        row.put("assistant_messages", 0);
        row.put("total_messages", 0);
        return row;
    }

    private String messageDate(Map<String, Object> message, Path fallbackFile) {
        String timestamp = stringValue(message.get("created_at"));
        if (timestamp.isBlank()) timestamp = stringValue(message.get("timestamp"));
        if (timestamp.length() >= 10) {
            String prefix = timestamp.substring(0, 10);
            if (parseDate(prefix, null) != null) return prefix;
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

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void increment(Map<String, Object> row, String key, long amount) {
        if (row == null) return;
        Object current = row.get(key);
        long value = current instanceof Number n ? n.longValue() : 0L;
        long next = value + amount;
        row.put(key, current instanceof Integer ? (int) next : next);
    }

    private long sum(List<Map<String, Object>> rows, String key) {
        long total = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (value instanceof Number n) total += n.longValue();
        }
        return total;
    }

    private long firstNumber(Object... values) {
        for (Object value : values) {
            if (value instanceof Number n) return n.longValue();
        }
        return 0L;
    }

    private Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private record Usage(long promptTokens, long completionTokens, long totalTokens) {}

    private record SessionStats(int toolCalls, boolean hasMessages) {}
}
