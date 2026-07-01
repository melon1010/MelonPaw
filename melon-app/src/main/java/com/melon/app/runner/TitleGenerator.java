package com.melon.app.runner;

import com.melon.core.util.HttpUtils;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话标题生成器. 对应 Python app/runner/title_generator.py.
 * <p>
 * 根据用户第一条消息调用 LLM 生成简短标题 (最多 20 字符).
 * 如果 LLM 调用失败, 则回退到从消息中截取.
 */
@Component
public class TitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(TitleGenerator.class);

    private static final int MAX_TITLE_LENGTH = 20;

    private String llmEndpoint;
    private String apiKey;
    private String model;

    // ======================== Configuration ========================

    /**
     * 配置 LLM 端点.
     *
     * @param llmEndpoint LLM API 地址
     * @param apiKey      API 密钥
     * @param model       模型名称
     */
    public void configure(String llmEndpoint, String apiKey, String model) {
        this.llmEndpoint = llmEndpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    // ======================== Public API ========================

    /**
     * 根据用户第一条消息生成对话标题.
     *
     * @param firstMessage 用户的第一条消息
     * @return 生成的标题 (最多 20 字符)
     */
    public String generate(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "New Chat";
        }

        // 尝试调用 LLM 生成标题
        String title = tryGenerateWithLLM(firstMessage);
        if (title != null && !title.isBlank()) {
            return truncate(title);
        }

        // 回退: 从消息中截取
        return fallbackTitle(firstMessage);
    }

    // ======================== Internal ========================

    /**
     * 调用 LLM 生成标题.
     */
    private String tryGenerateWithLLM(String message) {
        if (llmEndpoint == null || llmEndpoint.isBlank()) {
            return null;
        }
        try {
            String prompt = buildPrompt(message);
            String requestBody = JsonUtils.toJson(buildRequest(prompt));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + (apiKey != null ? apiKey : ""));
            headers.put("Content-Type", "application/json");

            String response = HttpUtils.postJson(llmEndpoint, requestBody, headers);
            if (response == null || response.isBlank()) {
                return null;
            }

            return extractTitleFromResponse(response);
        } catch (Exception e) {
            log.warn("LLM title generation failed, falling back: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建标题生成的 prompt.
     */
    private String buildPrompt(String message) {
        return "Generate a short title (max " + MAX_TITLE_LENGTH
                + " characters) for a conversation that starts with this message. "
                + "Reply with ONLY the title, no quotes or extra text.\n\n"
                + "Message: " + message;
    }

    /**
     * 构建请求体.
     */
    private Map<String, Object> buildRequest(String prompt) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model != null ? model : "qwen-turbo");
        request.put("prompt", prompt);
        request.put("max_tokens", 30);
        request.put("temperature", 0.3);
        return request;
    }

    /**
     * 从 LLM 响应中提取标题文本.
     */
    @SuppressWarnings("unchecked")
    private String extractTitleFromResponse(String response) {
        try {
            Map<String, Object> resp = JsonUtils.getMapper().readValue(response,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            // 兼容 DashScope 格式
            Object output = resp.get("output");
            if (output instanceof Map) {
                Object text = ((Map<String, Object>) output).get("text");
                if (text instanceof String) {
                    return ((String) text).trim();
                }
            }
            // 兼容 OpenAI 格式
            Object choices = resp.get("choices");
            if (choices instanceof java.util.List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object msg = choice.get("message");
                    if (msg instanceof Map<?, ?> msgMap) {
                        Object content = msgMap.get("content");
                        if (content instanceof String) {
                            return ((String) content).trim();
                        }
                    }
                }
            }
            // 直接 text 字段
            Object text = resp.get("text");
            if (text instanceof String) {
                return ((String) text).trim();
            }
        } catch (Exception e) {
            log.debug("Failed to parse LLM response for title: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 回退标题: 从消息中截取前若干字符.
     */
    private String fallbackTitle(String message) {
        String trimmed = message.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= MAX_TITLE_LENGTH) {
            return trimmed;
        }
        // 尝试在截断点附近找空格
        int cut = MAX_TITLE_LENGTH;
        while (cut > MAX_TITLE_LENGTH / 2 && trimmed.charAt(cut - 1) != ' ') {
            cut--;
        }
        if (cut <= MAX_TITLE_LENGTH / 2) {
            cut = MAX_TITLE_LENGTH;
        }
        return trimmed.substring(0, cut).trim() + "...";
    }

    /**
     * 截断标题到最大长度.
     */
    private String truncate(String title) {
        String trimmed = title.trim().replaceAll("[\"']", "");
        if (trimmed.length() <= MAX_TITLE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_TITLE_LENGTH);
    }
}
