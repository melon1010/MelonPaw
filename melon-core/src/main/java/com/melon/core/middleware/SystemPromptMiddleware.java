/**
 * @author melon
 */
package com.melon.core.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import com.melon.core.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 系统提示词中间件. 对应 Python _build_sys_prompt() + prompt.py + prompt_builder.py.
 * 读取 AGENTS.md/SOUL.md/PROFILE.md, 处理 heartbeat/memory 段, 注入 env_context.
 */
public class SystemPromptMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptMiddleware.class);
    private static final String HEARTBEAT_START = "<!-- heartbeat:start -->";
    private static final String HEARTBEAT_END = "<!-- heartbeat:end -->";
    private static final String MEMORY_START = "<!-- memory:start -->";
    private static final String MEMORY_END = "<!-- memory:end -->";

    private final Path workspaceDir;
    private final List<String> promptFiles;
    private final boolean heartbeatEnabled;

    public SystemPromptMiddleware(Path workspaceDir, List<String> promptFiles) {
        this(workspaceDir, promptFiles, true);
    }

    public SystemPromptMiddleware(Path workspaceDir, List<String> promptFiles, boolean heartbeatEnabled) {
        this.workspaceDir = workspaceDir;
        this.promptFiles = promptFiles != null ? promptFiles : List.of("AGENTS.md", "SOUL.md", "PROFILE.md");
        this.heartbeatEnabled = heartbeatEnabled;
    }

    @Override
    public Mono<String> onSystemPrompt(io.agentscope.core.agent.Agent agent, RuntimeContext ctx, String currentPrompt) {
        StringBuilder sb = new StringBuilder();

        // 1. 读取 prompt 文件
        for (String fileName : promptFiles) {
            Path file = workspaceDir.resolve(fileName);
            if (Files.exists(file)) {
                try {
                    String content = Files.readString(file);
                    content = processHeartbeatSection(content);
                    content = processMemorySection(content, ctx);
                    sb.append(content).append("\n\n");
                } catch (Exception e) {
                    log.warn("Failed to read prompt file {}", file, e);
                }
            }
        }

        // 2. 注入 env_context
        String envContext = buildEnvContext(ctx);
        if (!envContext.isEmpty()) {
            sb.append("## Environment\n\n").append(envContext);
        }

        // 3. 如果没有读到任何文件, 使用 AgentScope 已有内容
        if (sb.isEmpty()) {
            return Mono.just(currentPrompt);
        }

        return Mono.just(sb.toString());
    }

    private String processHeartbeatSection(String content) {
        int start = content.indexOf(HEARTBEAT_START);
        int end = content.indexOf(HEARTBEAT_END);
        if (start >= 0 && end > start) {
            if (heartbeatEnabled) {
                // heartbeat_enabled = true: 保留 heartbeat 段
                return content;
            } else {
                // heartbeat_enabled = false: 移除 heartbeat 段
                return content.substring(0, start) + content.substring(end + HEARTBEAT_END.length());
            }
        }
        return content;
    }

    private String processMemorySection(String content, RuntimeContext ctx) {
        int start = content.indexOf(MEMORY_START);
        int end = content.indexOf(MEMORY_END);
        if (start >= 0 && end > start) {
            // 替换 memory 段为记忆指导提示词
            String memoryPrompt = getMemoryPrompt();
            return content.substring(0, start) + memoryPrompt + content.substring(end + MEMORY_END.length());
        }
        return content;
    }

    private String getMemoryPrompt() {
        // 对应 Python memory/prompts.py 的 MEMORY_GUIDANCE
        return """
            <!-- memory:start -->
            You have access to a persistent memory system.
            Use the `memory_search` tool to recall past decisions and context.
            Use `memory_get` to read specific memory files.
            <!-- memory:end -->""";
    }

    private String buildEnvContext(RuntimeContext ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        if (ctx.getSessionId() != null) sb.append("- Session ID: ").append(ctx.getSessionId()).append("\n");
        if (ctx.getUserId() != null) sb.append("- User ID: ").append(ctx.getUserId()).append("\n");
        sb.append("- Working Directory: ").append(workspaceDir).append("\n");
        sb.append("- Shell: ").append(PlatformUtil.getDefaultShell()).append("\n");
        sb.append("- Project Dir: ").append(workspaceDir).append("\n");
        String channel = "console";
        try {
            String ctxChannel = ctx.get("channel", String.class);
            if (ctxChannel != null && !ctxChannel.isBlank()) {
                channel = ctxChannel;
            }
        } catch (Exception ignored) {
            // "channel" key not present in context, use default
        }
        sb.append("- Channel: ").append(channel).append("\n");
        return sb.toString();
    }
}
