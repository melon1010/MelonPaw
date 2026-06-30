/**
 * @author melon
 */
package com.melon.core.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息处理工具. 对应 Python agents/utils.py 的消息处理辅助方法.
 * <p>
 * 提取消息中的纯文本、工具调用, 并格式化消息列表为可读文本.
 * 消息以 Map 形式表示 (兼容 AgentScope Msg 序列化格式).
 */
public final class MessageProcessingUtil {

    private MessageProcessingUtil() {
    }

    /**
     * 提取消息中的纯文本内容.
     * <p>
     * 兼容多种消息格式:
     * <ul>
     *   <li>{@code content} 字段为字符串</li>
     *   <li>{@code content} 字段为列表 (包含 text 块)</li>
     *   <li>{@code text} 字段</li>
     * </ul>
     *
     * @param message 消息 Map
     * @return 纯文本内容, 无则返回空字符串
     */
    @SuppressWarnings("unchecked")
    public static String extractText(Map<String, Object> message) {
        if (message == null) {
            return "";
        }

        // 1. content 字段
        Object content = message.get("content");
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object block : list) {
                if (block instanceof Map<?, ?> blockMap) {
                    if ("text".equals(blockMap.get("type"))) {
                        Object text = blockMap.get("text");
                        if (text instanceof String) {
                            sb.append((String) text);
                        }
                    }
                } else if (block instanceof String s) {
                    sb.append(s);
                }
            }
            return sb.toString();
        }

        // 2. text 字段
        Object text = message.get("text");
        if (text instanceof String) {
            return (String) text;
        }

        return "";
    }

    /**
     * 提取消息中的工具调用 (tool_calls).
     *
     * @param message 消息 Map
     * @return 工具调用列表, 每项包含 name, arguments; 无则返回空列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractToolCalls(Map<String, Object> message) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (message == null) {
            return result;
        }

        Object toolCalls = message.get("tool_calls");
        if (toolCalls instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> call = new java.util.LinkedHashMap<>();
                    Object id = m.get("id");
                    if (id != null) {
                        call.put("id", id);
                    }
                    Object function = m.get("function");
                    if (function instanceof Map<?, ?> func) {
                        call.put("name", func.get("name"));
                        call.put("arguments", func.get("arguments"));
                    } else {
                        // 直接 name/arguments 格式
                        call.put("name", m.get("name"));
                        call.put("arguments", m.get("arguments"));
                    }
                    result.add(call);
                }
            }
        }

        return result;
    }

    /**
     * 提取消息中的工具结果 (tool_result).
     *
     * @param message 消息 Map
     * @return 工具结果内容, 无则返回 null
     */
    public static String extractToolResult(Map<String, Object> message) {
        if (message == null) {
            return null;
        }
        // tool_result 消息的 content 字段即为结果
        Object role = message.get("role");
        if ("tool".equals(role) || "function".equals(role)) {
            return extractText(message);
        }
        // 或 tool_result 类型
        Object type = message.get("type");
        if ("tool_result".equals(type)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /**
     * 格式化消息列表为可读文本.
     * <p>
     * 每条消息格式为: {@code [role] content}
     * 工具调用格式为: {@code [role] -> tool: name(args)}
     *
     * @param messages 消息列表
     * @return 格式化后的可读文本
     */
    public static String formatMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String role = strOrDefault(msg.get("role"), "unknown");
            String text = extractText(msg);
            List<Map<String, Object>> toolCalls = extractToolCalls(msg);

            if (!text.isEmpty()) {
                sb.append("[").append(role).append("] ").append(text);
            }
            for (Map<String, Object> call : toolCalls) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n");
                }
                sb.append("[").append(role).append("] -> tool: ")
                        .append(strOrDefault(call.get("name"), "?"))
                        .append("(")
                        .append(strOrDefault(call.get("arguments"), ""))
                        .append(")");
            }
            String toolResult = extractToolResult(msg);
            if (toolResult != null && !toolResult.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n");
                }
                sb.append("[tool_result] ").append(toolResult);
            }
        }
        return sb.toString();
    }

    /**
     * 获取消息角色.
     */
    public static String getRole(Map<String, Object> message) {
        if (message == null) {
            return "unknown";
        }
        return strOrDefault(message.get("role"), "unknown");
    }

    // ======================== Internal ========================

    private static String strOrDefault(Object value, String defaultValue) {
        return value != null ? value.toString() : defaultValue;
    }
}
