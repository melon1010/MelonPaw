package com.melon.tools.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * App-layer bridge for inter-agent chat tools.
 */
public final class AgentChatBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentChatBridge.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    public interface AgentExecutor {
        String execute(AgentRequest request) throws Exception;
    }

    public record AgentRequest(
            String toAgent,
            String text,
            String sessionId,
            String fromAgent,
            String rootSessionId,
            long timeoutSeconds,
            Map<String, Object> context
    ) {
        public AgentRequest(String toAgent, String text, String sessionId, String fromAgent,
                            String rootSessionId, long timeoutSeconds) {
            this(toAgent, text, sessionId, fromAgent, rootSessionId, timeoutSeconds, Map.of());
        }
    }

    private static volatile AgentExecutor executor;

    private AgentChatBridge() {}

    public static void setExecutor(AgentExecutor agentExecutor) {
        executor = agentExecutor;
        log.info("Agent chat bridge executor wired");
    }

    public static String execute(AgentRequest request) throws Exception {
        AgentExecutor current = executor;
        if (current == null) {
            throw new IllegalStateException("Agent chat executor is not wired");
        }
        return current.execute(request);
    }

    public static String normalizeId(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        while ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isBlank() ? null : value;
    }

    public static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static long longValue(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static Double doubleValue(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value != null) {
            String text = String.valueOf(value).toLowerCase(Locale.ROOT);
            if ("true".equals(text)) return true;
            if ("false".equals(text)) return false;
        }
        return fallback;
    }

    public static String callingAgentId(ToolCallParam param) {
        RuntimeContext ctx = param != null ? param.getRuntimeContext() : null;
        Object agentId = ctx != null ? ctx.get("agent_id") : null;
        if (agentId != null && !String.valueOf(agentId).isBlank()) {
            return String.valueOf(agentId);
        }
        String userId = ctx != null ? ctx.getUserId() : null;
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    public static String currentSessionId(ToolCallParam param) {
        RuntimeContext ctx = param != null ? param.getRuntimeContext() : null;
        if (ctx == null) return "";
        String sessionId = ctx.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        Object value = ctx.get("session_id");
        return value == null ? "" : String.valueOf(value);
    }

    public static String rootSessionId(ToolCallParam param) {
        RuntimeContext ctx = param != null ? param.getRuntimeContext() : null;
        if (ctx != null) {
            Object value = ctx.get("root_session_id");
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return currentSessionId(param);
    }

    public static String generateSessionId(String fromAgent, String toAgent) {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return fromAgent + ":to:" + toAgent + ":" + System.currentTimeMillis() + ":" + HexFormat.of().formatHex(bytes);
    }

    public static String generateSubagentSessionId() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "sub-" + HexFormat.of().formatHex(bytes);
    }

    public static String ensureIdentityPrefix(String text, String fromAgent) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.matches("^\\[Agent\\s+\\S+.*") || stripped.matches("^\\[来自智能体\\s+\\S+.*")) {
            return text;
        }
        return "[Agent " + fromAgent + " requesting] " + stripped;
    }

    public static String formatAgentChatText(String text, String sessionId) {
        String body = text == null || text.isBlank() ? "(No text content in response)" : text;
        if (sessionId == null || sessionId.isBlank()) return body;
        return "[SESSION: " + sessionId + "]\n\n" + body;
    }
}
