/**
 * @author melon
 */
package com.melon.core.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息请求标准化. 对应 Python agents/utils.py 的消息标准化部分.
 * <p>
 * 跨 Provider 兼容的消息格式标准化.
 * 不同模型 Provider (OpenAI / Anthropic / DashScope) 对消息格式有细微差异,
 * 本工具确保消息格式符合目标 Provider 的要求.
 */
public final class MessageRequestNormalizer {

    private MessageRequestNormalizer() {
    }

    // ======================== Provider Types ========================

    /** Provider 类型. */
    public enum ProviderType {
        OPENAI,       // OpenAI / DashScope 兼容 OpenAI 格式
        ANTHROPIC,    // Anthropic Claude
        GEMINI,       // Google Gemini
        OLLAMA,       // Ollama 本地
        GENERIC       // 通用格式
    }

    // ======================== Public API ========================

    /**
     * 标准化消息列表, 适配指定 Provider.
     *
     * @param messages    原始消息列表
     * @param providerType 目标 Provider 类型
     * @return 标准化后的消息列表
     */
    public static List<Map<String, Object>> normalize(
            List<Map<String, Object>> messages, ProviderType providerType) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            normalized.add(normalizeMessage(msg, providerType));
        }

        // Provider 特定后处理
        switch (providerType) {
            case ANTHROPIC -> normalized = normalizeForAnthropic(normalized);
            case GEMINI -> normalized = normalizeForGemini(normalized);
            default -> { /* OpenAI / Ollama / Generic 不需要额外处理 */ }
        }

        return normalized;
    }

    /**
     * 标准化单条消息.
     *
     * @param message      原始消息
     * @param providerType 目标 Provider 类型
     * @return 标准化后的消息
     */
    public static Map<String, Object> normalizeMessage(
            Map<String, Object> message, ProviderType providerType) {
        Map<String, Object> result = new LinkedHashMap<>(message);

        // 确保 role 字段存在
        Object role = result.get("role");
        if (role == null) {
            result.put("role", "user");
        }

        // 标准化 content 字段: 确保是字符串
        Object content = result.get("content");
        if (content == null) {
            result.put("content", "");
        } else if (content instanceof List<?> list) {
            // content 是块列表时, 提取纯文本 (仅对非 OpenAI 格式)
            if (providerType != ProviderType.OPENAI) {
                result.put("content", extractTextFromContentBlocks(list));
            }
        }

        // 清理空字段
        result.values().removeIf(v -> v == null);

        return result;
    }

    /**
     * 将模型标识符解析为 provider:model 格式.
     *
     * @param modelId 模型标识符 (如 "dashscope:qwen-plus" 或 "qwen-plus")
     * @return [provider, model] 数组
     */
    public static String[] parseModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return new String[]{"dashscope", "qwen-plus"};
        }
        int colonIdx = modelId.indexOf(':');
        if (colonIdx > 0) {
            return new String[]{
                    modelId.substring(0, colonIdx),
                    modelId.substring(colonIdx + 1)
            };
        }
        // 无前缀, 根据模型名猜测 provider
        String provider = guessProvider(modelId);
        return new String[]{provider, modelId};
    }

    /**
     * 根据模型名猜测 Provider.
     */
    public static String guessProvider(String modelName) {
        if (modelName == null) {
            return "dashscope";
        }
        String lower = modelName.toLowerCase();
        if (lower.startsWith("qwen") || lower.startsWith("deepseek")) {
            return "dashscope";
        }
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3")) {
            return "openai";
        }
        if (lower.startsWith("claude")) {
            return "anthropic";
        }
        if (lower.startsWith("gemini")) {
            return "gemini";
        }
        if (lower.startsWith("llama") || lower.startsWith("mistral") || lower.startsWith("phi")) {
            return "ollama";
        }
        return "dashscope";
    }

    // ======================== Provider-Specific Normalization ========================

    /**
     * Anthropic 特定标准化:
     * <ul>
     *   <li>system 消息需要单独传递 (不在 messages 列表中)</li>
     *   <li>tool 角色映射为 user + tool_result</li>
     * </ul>
     */
    private static List<Map<String, Object>> normalizeForAnthropic(
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", "user"));
            if ("system".equals(role)) {
                // Anthropic 的 system 消息应在顶层参数, 不在 messages 中
                // 这里保留但在调用方需额外处理
                result.add(msg);
            } else if ("tool".equals(role)) {
                // 转为 user 角色带 tool_result
                Map<String, Object> converted = new LinkedHashMap<>();
                converted.put("role", "user");
                Map<String, Object> toolResult = new LinkedHashMap<>();
                toolResult.put("type", "tool_result");
                toolResult.put("content", msg.getOrDefault("content", ""));
                if (msg.get("tool_call_id") != null) {
                    toolResult.put("tool_use_id", msg.get("tool_call_id"));
                }
                converted.put("content", List.of(toolResult));
                result.add(converted);
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * Gemini 特定标准化:
     * <ul>
     *   <li>system 消息合并到第一条 user 消息前缀</li>
     *   <li>角色映射: assistant -> model</li>
     * </ul>
     */
    private static List<Map<String, Object>> normalizeForGemini(
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        StringBuilder systemPrefix = new StringBuilder();

        // 收集 system 消息
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", "user"));
            if ("system".equals(role)) {
                if (systemPrefix.length() > 0) {
                    systemPrefix.append("\n\n");
                }
                systemPrefix.append(msg.getOrDefault("content", ""));
            }
        }

        // 转换非 system 消息
        boolean firstUser = true;
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", "user"));
            if ("system".equals(role)) {
                continue;
            }
            Map<String, Object> converted = new LinkedHashMap<>(msg);
            // assistant -> model
            if ("assistant".equals(role)) {
                converted.put("role", "model");
            }
            // 第一个 user 消息加上 system 前缀
            if ("user".equals(role) && firstUser && systemPrefix.length() > 0) {
                String originalContent = String.valueOf(converted.getOrDefault("content", ""));
                converted.put("content", systemPrefix + "\n\n" + originalContent);
                firstUser = false;
            } else if ("user".equals(role)) {
                firstUser = false;
            }
            result.add(converted);
        }
        return result;
    }

    // ======================== Helpers ========================

    /**
     * 从 content 块列表中提取纯文本.
     */
    @SuppressWarnings("unchecked")
    private static String extractTextFromContentBlocks(List<?> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map) {
                Object type = map.get("type");
                Object text = map.get("text");
                if ("text".equals(type) && text instanceof String str) {
                    sb.append(str);
                }
            } else if (block instanceof String str) {
                sb.append(str);
            }
        }
        return sb.toString();
    }
}
