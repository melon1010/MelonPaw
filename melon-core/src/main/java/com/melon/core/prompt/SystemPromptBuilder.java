/**
 * @author melon
 */
package com.melon.core.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 系统提示词构建器. 对应 Python prompt.py 的 build_system_prompt_from_working_dir().
 */
public class SystemPromptBuilder {

    public static final List<String> DEFAULT_FILES = List.of("AGENTS.md", "SOUL.md", "PROFILE.md");
    private static final String HEARTBEAT_START = "<!-- heartbeat:start -->";
    private static final String HEARTBEAT_END = "<!-- heartbeat:end -->";

    /**
     * 从工作目录构建系统提示词.
     */
    public String build(Path workspaceDir, List<String> promptFiles, Map<String, Object> envContext) {
        StringBuilder sb = new StringBuilder();
        List<String> files = promptFiles != null ? promptFiles : DEFAULT_FILES;

        for (String fileName : files) {
            Path file = workspaceDir.resolve(fileName);
            if (Files.exists(file)) {
                try {
                    String content = Files.readString(file);
                    content = removeHeartbeatSection(content);
                    sb.append(content).append("\n\n");
                } catch (Exception ignored) {}
            }
        }

        if (envContext != null && !envContext.isEmpty()) {
            sb.append("## Environment\n\n");
            for (var entry : envContext.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.isEmpty() ? "You are a helpful assistant." : sb.toString();
    }

    private String removeHeartbeatSection(String content) {
        int start = content.indexOf(HEARTBEAT_START);
        int end = content.indexOf(HEARTBEAT_END);
        if (start >= 0 && end > start) {
            return content.substring(0, start) + content.substring(end + HEARTBEAT_END.length());
        }
        return content;
    }
}
