package com.melon.channels;

import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

public class ChannelMessageRenderer {

    public String render(List<Map<String, Object>> messages, Map<String, Object> config) {
        StringBuilder text = new StringBuilder();
        boolean hideThinking = Boolean.TRUE.equals(config.get("filter_thinking"));
        boolean hideTools = Boolean.TRUE.equals(config.get("filter_tool_messages"));
        for (Map<String, Object> message : messages != null ? messages : List.<Map<String, Object>>of()) {
            String type = stringValue(message.get("type"));
            if ("reasoning".equals(type) && hideThinking) continue;
            if (type.startsWith("plugin_") && hideTools) continue;
            appendContent(text, message.get("content"));
        }
        String body = text.toString().trim();
        String prefix = stringValue(config.get("bot_prefix"));
        if (!prefix.isBlank() && !body.isBlank()) {
            return prefix + "  " + body;
        }
        return body;
    }

    private void appendContent(StringBuilder out, Object raw) {
        if (!(raw instanceof List<?> items)) return;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> content)) continue;
            if (content.get("text") != null) {
                appendLine(out, stringValue(content.get("text")));
            } else if (content.get("data") instanceof Map<?, ?> data) {
                appendLine(out, stringValue(data.get("output"), stringValue(data.get("arguments"), "")));
            }
        }
    }

    private void appendLine(StringBuilder out, String text) {
        if (text == null || text.isBlank()) return;
        if (!out.isEmpty()) out.append("\n\n");
        out.append(text);
    }
}
