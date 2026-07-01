package com.melon.core.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具消息处理. 对应 Python agents/utils.py 的工具消息部分.
 * <p>
 * 构建 tool_use 和 tool_result 消息对, 解析工具调用结果,
 * 格式化工具结果为 LLM 可读文本.
 */
public final class ToolMessageUtils {

    private ToolMessageUtils() {
    }

    // ======================== Tool Use Message ========================

    /**
     * 构建 tool_use 消息 (assistant 角色, 包含工具调用请求).
     *
     * @param toolCallId  工具调用 ID
     * @param toolName    工具名称
     * @param arguments   工具参数 (Map 或 JSON 字符串)
     * @return tool_use 消息
     */
    public static Map<String, Object> buildToolUseMessage(
            String toolCallId, String toolName, Object arguments) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", toolCallId);
        call.put("type", "function");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toolName);
        function.put("arguments", arguments instanceof String
                ? arguments
                : com.melon.core.util.JsonUtils.toJson(arguments));
        call.put("function", function);
        toolCalls.add(call);
        message.put("tool_calls", toolCalls);

        return message;
    }

    /**
     * 构建 tool_result 消息 (tool 角色, 包含工具执行结果).
     *
     * @param toolCallId 工具调用 ID (对应 tool_use 的 id)
     * @param result     工具执行结果
     * @param isError    是否为错误结果
     * @return tool_result 消息
     */
    public static Map<String, Object> buildToolResultMessage(
            String toolCallId, Object result, boolean isError) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", result instanceof String
                ? result
                : com.melon.core.util.JsonUtils.toJson(result));
        if (isError) {
            message.put("is_error", true);
        }
        return message;
    }

    // ======================== Tool Result Parsing ========================

    /**
     * 解析工具调用结果.
     *
     * @param resultMessage 工具结果消息 (Map 格式)
     * @return 解析后的结果对象
     */
    @SuppressWarnings("unchecked")
    public static ToolResult parseToolResult(Map<String, Object> resultMessage) {
        if (resultMessage == null) {
            return new ToolResult("", false);
        }

        String toolCallId = String.valueOf(resultMessage.getOrDefault("tool_call_id", ""));
        Object content = resultMessage.get("content");
        boolean isError = Boolean.TRUE.equals(resultMessage.get("is_error"));

        String resultText;
        if (content instanceof String str) {
            resultText = str;
        } else if (content == null) {
            resultText = "";
        } else {
            resultText = com.melon.core.util.JsonUtils.toJson(content);
        }

        return new ToolResult(toolCallId, resultText, isError);
    }

    // ======================== Tool Result Formatting ========================

    /**
     * 格式化工具结果为 LLM 可读文本.
     *
     * @param toolName    工具名称
     * @param result      工具执行结果
     * @param isError     是否为错误
     * @return 格式化的文本
     */
    public static String formatToolResult(String toolName, Object result, boolean isError) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Tool Result");
        if (toolName != null && !toolName.isBlank()) {
            sb.append(" - ").append(toolName);
        }
        if (isError) {
            sb.append(" (ERROR)");
        }
        sb.append("]\n");

        if (result == null) {
            sb.append("(no output)");
        } else if (result instanceof String str) {
            sb.append(str);
        } else {
            sb.append(com.melon.core.util.JsonUtils.toJson(result));
        }

        return sb.toString();
    }

    /**
     * 格式化工具调用请求为可读文本.
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 格式化的文本
     */
    public static String formatToolCall(String toolName, Object arguments) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Tool Call - ").append(toolName != null ? toolName : "unknown").append("]\n");
        if (arguments == null) {
            sb.append("arguments: (none)");
        } else if (arguments instanceof String str) {
            sb.append("arguments: ").append(str);
        } else {
            sb.append("arguments: ").append(com.melon.core.util.JsonUtils.toJson(arguments));
        }
        return sb.toString();
    }

    /**
     * 构建工具调用请求和结果的配对消息.
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @param result     工具执行结果
     * @param isError    结果是否为错误
     * @return 包含 tool_use 和 tool_result 两条消息的列表
     */
    public static List<Map<String, Object>> buildToolMessagePair(
            String toolCallId, String toolName,
            Object arguments, Object result, boolean isError) {
        List<Map<String, Object>> pair = new ArrayList<>(2);
        pair.add(buildToolUseMessage(toolCallId, toolName, arguments));
        pair.add(buildToolResultMessage(toolCallId, result, isError));
        return pair;
    }

    // ======================== Result Class ========================

    /**
     * 工具结果数据.
     */
    public static class ToolResult {
        private final String toolCallId;
        private final String content;
        private final boolean error;

        public ToolResult(String toolCallId, String content, boolean error) {
            this.toolCallId = toolCallId;
            this.content = content;
            this.error = error;
        }

        public ToolResult(String content, boolean error) {
            this("", content, error);
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getContent() {
            return content;
        }

        public boolean isError() {
            return error;
        }
    }
}
