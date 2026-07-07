package com.melon.app.service;

import com.melon.core.agent.ToolkitContributor;
import com.melon.core.config.AgentConfig;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

@Component
public class HistoryToolkitContributor implements ToolkitContributor {

    private final HistoryStore historyStore;

    public HistoryToolkitContributor(HistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    @Override
    public void contribute(String agentId, AgentConfig agentConfig, Path workspaceDir, Toolkit toolkit) {
        if (!enabled(agentConfig, "recall_history")) return;
        toolkit.registerAgentTool(new RecallHistoryTool(agentId, historyStore));
    }

    private boolean enabled(AgentConfig config, String toolName) {
        if (config.getTools() == null || config.getTools().getBuiltinTools() == null) return true;
        var tool = config.getTools().getBuiltinTools().get(toolName);
        return tool == null || tool.isEnabled();
    }

    private static final class RecallHistoryTool extends ToolBase {
        private final String agentId;
        private final HistoryStore historyStore;

        RecallHistoryTool(String agentId, HistoryStore historyStore) {
            super(ToolBase.builder()
                    .name("recall_history")
                    .description("Read the agent's durable conversation history from history.db. Actions: search, session, range, tool.")
                    .inputSchema(schema())
                    .readOnly(true)
                    .concurrencySafe(true));
            this.agentId = agentId;
            this.historyStore = historyStore;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> handle(param.getInput()))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private ToolResultBlock handle(Map<String, Object> input) {
            String action = stringValue(input.get("action"), "search");
            int limit = intValue(input.get("limit"), 20);
            List<Map<String, Object>> rows = switch (action) {
                case "session" -> historyStore.session(agentId, stringValue(input.get("session_id"), "default"), limit);
                case "range" -> historyStore.range(agentId, longValue(input.get("lo"), 1), longValue(input.get("hi"), 1));
                case "tool" -> historyStore.recallTool(agentId, stringValue(input.get("tool_call_id"), ""), limit);
                case "search" -> historyStore.search(agentId, stringValue(input.get("query"), ""), limit);
                default -> null;
            };
            if (rows == null) return ToolResultBlock.error("Unknown action: " + action);
            return ToolResultBlock.text(JsonUtils.toJson(Map.of("action", action, "rows", rows)));
        }

        private static Map<String, Object> schema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "action", Map.of("type", "string", "enum", List.of("search", "session", "range", "tool")),
                            "query", Map.of("type", "string", "description", "Keyword query for action=search"),
                            "session_id", Map.of("type", "string", "description", "Session id for action=session"),
                            "lo", Map.of("type", "integer", "description", "Start seq for action=range"),
                            "hi", Map.of("type", "integer", "description", "End seq for action=range"),
                            "tool_call_id", Map.of("type", "string", "description", "Tool call id for action=tool"),
                            "limit", Map.of("type", "integer", "default", 20)
                    )
            );
        }

        private int intValue(Object value, int fallback) {
            if (value instanceof Number number) return number.intValue();
            try {
                return value == null ? fallback : Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private long longValue(Object value, long fallback) {
            if (value instanceof Number number) return number.longValue();
            try {
                return value == null ? fallback : Long.parseLong(String.valueOf(value));
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }
}
