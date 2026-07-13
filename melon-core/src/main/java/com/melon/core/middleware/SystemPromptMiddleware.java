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
    private static final String TOOL_EVIDENCE_GUIDANCE = """
            ## Tool Evidence

            When describing tool or browser activity in this session, state URLs, statuses, errors, and results
            only when they appear in tool-result blocks. Do not invent failed sites, retries, captchas, or
            successful fallbacks. If no matching result exists, say that it was not recorded.""";

    private final Path workspaceDir;
    private final List<String> promptFiles;
    private final boolean heartbeatEnabled;
    private final boolean memoryEnabled;

    public SystemPromptMiddleware(Path workspaceDir, List<String> promptFiles) {
        this(workspaceDir, promptFiles, true, false);
    }

    public SystemPromptMiddleware(Path workspaceDir, List<String> promptFiles, boolean heartbeatEnabled) {
        this(workspaceDir, promptFiles, heartbeatEnabled, false);
    }

    public SystemPromptMiddleware(Path workspaceDir, List<String> promptFiles, boolean heartbeatEnabled,
                                  boolean memoryEnabled) {
        this.workspaceDir = workspaceDir;
        this.promptFiles = promptFiles != null ? promptFiles : List.of("AGENTS.md", "SOUL.md", "PROFILE.md");
        this.heartbeatEnabled = heartbeatEnabled;
        this.memoryEnabled = memoryEnabled;
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

        String skillContext = buildSkillContext();
        if (!skillContext.isEmpty()) {
            sb.append("\n\n").append(skillContext);
        }

        // Apply the evidence rule even when the workspace has no prompt files.
        String prompt = sb.isEmpty() ? currentPrompt : sb.toString();
        if (prompt == null || prompt.isBlank()) return Mono.just(TOOL_EVIDENCE_GUIDANCE);
        if (prompt.contains("## Tool Evidence")) return Mono.just(prompt);
        return Mono.just(prompt.strip() + "\n\n" + TOOL_EVIDENCE_GUIDANCE);
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
            String memoryPrompt = memoryEnabled ? getMemoryPrompt() : "";
            return content.substring(0, start) + memoryPrompt + content.substring(end + MEMORY_END.length());
        }
        return content;
    }

    private String getMemoryPrompt() {
        // 对应 Python memory/prompts.py 的 MEMORY_GUIDANCE
        return """
            <!-- memory:start -->
            You have access to a persistent memory system.
            `memory_search` performs BM25 keyword ranking over MEMORY.md and memory/*.md; use short,
            specific keywords, identifiers, file names, dates, people, or error codes instead of
            broad natural-language questions.
            Use `memory_get` after `memory_search` to read surrounding lines from specific memory files.
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

    private String buildSkillContext() {
        Path skillsDir = workspaceDir.resolve("skills");
        if (!Files.exists(skillsDir)) return "";
        try (var stream = Files.list(skillsDir)) {
            List<String> names = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.exists(path.resolve(".disabled")))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
            if (names.isEmpty()) return "";
            return "## Available Skills\n\n"
                    + "Use `materialize_skill` to load these workspace skills when they are relevant: "
                    + String.join(", ", names)
                    + ".";
        } catch (Exception e) {
            log.warn("Failed to list workspace skills {}", skillsDir, e);
            return "";
        }
    }
}
