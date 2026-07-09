package com.melon.tools.shell;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.env.EnvBridge;
import com.melon.core.util.PlatformUtil;
import com.melon.core.util.WorkspacePathResolver;
import io.agentscope.core.agent.RuntimeContext;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具. 对应 Python shell.py:execute_shell_command.
 * 跨平台: Windows cmd /D /S /C, Unix sh -c.
 * 自杀防护: 检测 taskkill/kill 针对当前进程.
 */
public class ExecuteShellCommandTool extends ToolBase {

    private static final ConcurrentHashMap<String, Set<Process>> RUNNING = new ConcurrentHashMap<>();

    private final WorkspacePathResolver pathResolver;
    private final double defaultTimeoutSeconds;
    private final String shellExecutable;

    public ExecuteShellCommandTool() {
        this(null, 60.0, null);
    }

    public ExecuteShellCommandTool(String workspaceDir) {
        this(workspaceDir, 60.0, null);
    }

    public ExecuteShellCommandTool(String workspaceDir, double defaultTimeoutSeconds, String shellExecutable) {
        super(ToolBase.builder()
                .name("execute_shell_command")
                .description("Execute a shell command and return stdout/stderr")
                .inputSchema(parseSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "command": {
                              "type": "string",
                              "description": "Shell command to execute"
                            },
                            "timeout": {
                              "type": "number",
                              "description": "Timeout in seconds",
                              "default": 60
                            },
                            "cwd": {
                              "type": "string",
                              "description": "Working directory for the command"
                            },
                            "working_directory": {
                              "type": "string",
                              "description": "Alias for cwd, accepted for compatibility"
                            }
                          },
                          "required": ["command"]
                        }"""))
                .readOnly(false)
                .concurrencySafe(false));
        this.pathResolver = new WorkspacePathResolver(workspaceDir);
        this.defaultTimeoutSeconds = sanitizeTimeout(defaultTimeoutSeconds, 60.0);
        this.shellExecutable = shellExecutable == null || shellExecutable.isBlank() ? null : shellExecutable.trim();
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String command = (String) param.getInput().get("command");
        if (command == null || command.isBlank()) {
            return Mono.just(error(param, "Error: command is required"));
        }
        double timeout = normalizeTimeout(param.getInput().get("timeout"));
        String cwd = (String) param.getInput().getOrDefault("cwd", param.getInput().get("working_directory"));

        // 自杀防护
        if (isDangerousSelfKill(command)) {
            return Mono.just(error(param, "Error: Self-kill command detected and blocked."));
        }

        String sessionId = sessionId(param);
        return Mono.fromCallable(() -> executeCommand(command, timeout, cwd, sessionId))
                .map(result -> ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name("execute_shell_command")
                        .output(java.util.List.of(io.agentscope.core.message.TextBlock.builder().text(result).build()))
                        .build())
                .onErrorResume(e -> Mono.just(error(param, "Error: " + e.getMessage())));
    }

    public static int cancelSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        Set<Process> processes = RUNNING.remove(sessionId);
        if (processes == null || processes.isEmpty()) return 0;
        processes.forEach(ExecuteShellCommandTool::destroyProcessTree);
        return processes.size();
    }

    private String executeCommand(String command, double timeout, String cwd, String sessionId) throws Exception {
        boolean isWindows = PlatformUtil.isWindows();

        ProcessBuilder pb;
        if (isWindows) {
            pb = windowsShell(command);
        } else {
            pb = new ProcessBuilder(shellExecutable != null ? shellExecutable : "sh", "-c", command);
        }

        if (cwd != null) {
            pb.directory(pathResolver.resolve(cwd).toFile());
        } else {
            pb.directory(pathResolver.resolveOptional(null).toFile());
        }
        pb.redirectErrorStream(true);
        EnvBridge.applyToProcessEnv(pb.environment());

        Process process = pb.start();
        register(sessionId, process);

        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception ignored) {
                // The process may be destroyed on timeout while the stream is closing.
            }
        }, "melon-shell-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean interrupted = false;
        try {
            boolean finished = process.waitFor((long) (timeout * 1000), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
                process.waitFor(2, TimeUnit.SECONDS);
                output.append("\n[Command timed out after ").append(timeout).append("s]");
            }
        } catch (InterruptedException e) {
            destroyProcessTree(process);
            interrupted = true;
            output.append("\n[Command interrupted]");
        } finally {
            unregister(sessionId, process);
        }
        outputReader.join(1000);
        if (interrupted) Thread.currentThread().interrupt();

        return output.toString();
    }

    private static void register(String sessionId, Process process) {
        if (sessionId == null || sessionId.isBlank() || process == null) return;
        RUNNING.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(process);
    }

    private static void unregister(String sessionId, Process process) {
        if (sessionId == null || sessionId.isBlank() || process == null) return;
        Set<Process> processes = RUNNING.get(sessionId);
        if (processes == null) return;
        processes.remove(process);
        if (processes.isEmpty()) RUNNING.remove(sessionId, processes);
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) return;
        ProcessHandle handle = process.toHandle();
        try {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // macOS sandboxed JVMs can deny process-tree enumeration; killing the shell still works.
        }
        process.destroyForcibly();
    }

    private String sessionId(ToolCallParam param) {
        RuntimeContext ctx = param != null ? param.getRuntimeContext() : null;
        if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            return ctx.getSessionId();
        }
        Object raw = ctx != null ? ctx.get("session_id") : null;
        return raw != null ? String.valueOf(raw) : "";
    }

    private double normalizeTimeout(Object raw) {
        double value = raw instanceof Number number ? number.doubleValue() : parseTimeout(raw);
        if (Double.isNaN(value) || value <= 0 || value == 60.0) {
            value = defaultTimeoutSeconds;
        }
        return sanitizeTimeout(value, defaultTimeoutSeconds);
    }

    private double parseTimeout(Object raw) {
        if (raw == null) return 60.0;
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (Exception ignored) {
            return 60.0;
        }
    }

    private double sanitizeTimeout(double value, double fallback) {
        if (Double.isNaN(value) || value <= 0) {
            value = fallback;
        }
        return Math.min(Math.max(value, 1.0), 600.0);
    }

    private ProcessBuilder windowsShell(String command) {
        if (shellExecutable == null) {
            return new ProcessBuilder("cmd", "/D", "/S", "/C", command);
        }
        String lower = shellExecutable.toLowerCase();
        if (lower.endsWith("cmd") || lower.endsWith("cmd.exe")) {
            return new ProcessBuilder(shellExecutable, "/D", "/S", "/C", command);
        }
        if (lower.endsWith("powershell") || lower.endsWith("powershell.exe")
                || lower.endsWith("pwsh") || lower.endsWith("pwsh.exe")) {
            return new ProcessBuilder(shellExecutable, "-NoProfile", "-Command", command);
        }
        return new ProcessBuilder(shellExecutable, "-c", command);
    }

    private ToolResultBlock error(ToolCallParam param, String message) {
        return ToolResultBlock.builder()
                .id(param.getToolUseBlock().getId())
                .name("execute_shell_command")
                .output(java.util.List.of(io.agentscope.core.message.TextBlock.builder().text(message).build()))
                .state(io.agentscope.core.message.ToolResultState.ERROR)
                .build();
    }

    private boolean isDangerousSelfKill(String command) {
        String lower = command.toLowerCase();
        long pid = ProcessHandle.current().pid();
        return DANGER_PATTERNS.stream().anyMatch(p -> {
            var m = p.matcher(lower);
            return m.find() && (lower.contains(String.valueOf(pid)) || lower.contains("current") || lower.contains("self"));
        });
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) return true;
        String rule = ruleContent.trim();
        Object raw = toolInput != null ? toolInput.get("command") : null;
        if (raw == null) return false;
        String command = String.valueOf(raw);
        if ("delete".equalsIgnoreCase(rule)) {
            return looksLikeDeleteCommand(command);
        }
        if ("qwenpaw-dangerous-shell".equalsIgnoreCase(rule)) {
            return looksLikeQwenPawDangerousShellCommand(command);
        }
        return false;
    }

    private boolean looksLikeDeleteCommand(String command) {
        String normalized = command == null ? "" : command.toLowerCase();
        return DELETE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private boolean looksLikeQwenPawDangerousShellCommand(String command) {
        if (command == null || command.strip().startsWith("#")) return false;
        return QWENPAW_DANGEROUS_SHELL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(command).find())
                || (QWENPAW_PROCESS_KILL_PATTERN.matcher(command).find()
                && !QWENPAW_PROCESS_KILL_EXCLUDE.matcher(command).find());
    }

    private static Pattern shellPattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static final java.util.List<Pattern> DANGER_PATTERNS = java.util.List.of(
            Pattern.compile("taskkill\\s+/F\\s+/T\\s+/PID"),
            Pattern.compile("kill\\s+-9\\s+\\$\\$"),
            Pattern.compile("stop-process\\s+-id\\s+\\$PID")
    );

    private static final java.util.List<Pattern> DELETE_PATTERNS = java.util.List.of(
            Pattern.compile("(^|[;&|()\\s])rm\\s+(-[a-z]*\\s+)*(-r|-R|-f|-rf|-fr|--recursive|--force)\\b"),
            Pattern.compile("(^|[;&|()\\s])rmdir\\b"),
            Pattern.compile("(^|[;&|()\\s])unlink\\b"),
            Pattern.compile("(^|[;&|()\\s])shred\\b"),
            Pattern.compile("(^|[;&|()\\s])trash\\b"),
            Pattern.compile("(^|[;&|()\\s])git\\s+(rm|clean)\\b"),
            Pattern.compile("\\bfind\\b.*\\s-delete\\b"),
            Pattern.compile("(^|[;&|()\\s])del\\s+(/[^\\s]+\\s+)*[^\\s]+"),
            Pattern.compile("(^|[;&|()\\s])erase\\s+(/[^\\s]+\\s+)*[^\\s]+"),
            Pattern.compile("\\bremove-item\\b")
    );

    // Ported from QwenPaw security/tool_guard/rules/dangerous_shell_commands.yaml.
    private static final java.util.List<Pattern> QWENPAW_DANGEROUS_SHELL_PATTERNS = java.util.List.of(
            shellPattern("\\brm\\b"),
            shellPattern("\\bdel\\b"),
            shellPattern("\\bRemove-Item\\b"),
            shellPattern("\\bmv\\b"),
            shellPattern("\\bmkfs(\\.[a-zA-Z0-9_]+)?\\b"),
            shellPattern("\\bmke2fs\\b"),
            shellPattern("\\bdd\\s+.*of=\\/dev\\/"),
            shellPattern(">\\s*\\/dev\\/(sd[a-z][0-9]*|vd[a-z][0-9]*|nvme\\d+n\\d+(p\\d+)?)"),
            shellPattern(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            shellPattern("\\bkill\\s+-9\\s+(-1\\b|1\\b)"),
            shellPattern("\\b(curl|wget)\\b\\s+.*\\|.*\\b(bash|sh|zsh|ash|dash)\\b"),
            shellPattern("\\/dev\\/(tcp|udp)\\/"),
            shellPattern("\\bnc\\s+.*-e\\s*\\S+"),
            shellPattern("\\bncat\\s+.*-e\\s*\\S+"),
            shellPattern("\\bsocat\\s+.*EXEC:"),
            shellPattern("\\bcrontab\\b"),
            shellPattern("\\bauthorized_keys\\b"),
            shellPattern("\\/etc\\/sudoers"),
            shellPattern("\\/etc\\/crontab"),
            shellPattern("\\bchmod\\s+-[a-zA-Z]*R[a-zA-Z]*\\s+(777|a\\+rwx)\\s+\\/"),
            shellPattern("\\bchattr\\s+\\+i"),
            shellPattern("\\bbase64\\s+(-d|--decode)\\s*\\|\\s*\\b(bash|sh|zsh)\\b"),
            shellPattern("\\b(reboot|shutdown|halt|poweroff)\\b"),
            shellPattern("\\binit\\s+(0|6)\\b"),
            shellPattern("\\btelinit\\s+(0|6)\\b"),
            shellPattern("\\bShutdown-Computer\\b"),
            shellPattern("\\bRestart-Computer\\b"),
            shellPattern("\\bsystemctl\\s+(restart|stop|start|reload|kill)\\b"),
            shellPattern("\\bservice\\s+\\S+\\s+(restart|stop|start|reload)\\b"),
            shellPattern("\\b(sc|net)\\s+(start|stop|restart)\\b"),
            shellPattern("\\blaunchctl\\s+(load|unload|stop|start|kickstart|kill)\\b"),
            shellPattern("\\brc-service\\s+(restart|stop|start)\\b"),
            shellPattern("\\b(pkill|killall)\\b"),
            shellPattern("\\btaskkill\\s+\\/F\\b"),
            shellPattern("\\bStop-Process\\b.*-Force\\b"),
            shellPattern("\\bsudo\\s+"),
            shellPattern("\\bsu\\b"),
            shellPattern("\\bdoas\\s+"),
            shellPattern("\\bpkexec\\b"),
            shellPattern("\\brunas\\s+\\/user:"),
            shellPattern("\\$IFS(?![A-Za-z0-9_])"),
            shellPattern("\\$\\{[^}]*IFS"),
            shellPattern("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]"),
            shellPattern("[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"),
            shellPattern("\\/proc\\/(?:self|\\d+)\\/environ(?:\\b|$)"),
            shellPattern("\\bjq\\b.*\\bsystem\\s*\\("),
            shellPattern("\\bjq\\b.*(?:\\s-f\\b|\\s--from-file\\b|\\s--rawfile\\b|\\s--slurpfile\\b|\\s-L\\b|\\s--library-path\\b)"),
            shellPattern("\\bzmodload\\b"),
            shellPattern("\\bemulate\\b(?:\\s+-\\S+)*\\s+-c\\b"),
            shellPattern("\\b(sysopen|sysread|syswrite|sysseek)\\b"),
            shellPattern("\\b(zpty|ztcp|zsocket)\\b"),
            shellPattern("\\bzf_(rm|mv|ln|chmod|chown|mkdir|rmdir|chgrp)\\b"),
            shellPattern("\\bfc\\b.*\\s-\\S*e")
    );

    private static final Pattern QWENPAW_PROCESS_KILL_PATTERN =
            shellPattern("\\bkill\\s+(-(9|KILL|15|TERM|1|HUP|2|INT)\\s+)?[^-\\s]");
    private static final Pattern QWENPAW_PROCESS_KILL_EXCLUDE =
            shellPattern("kill\\s+\\$\\$");
}
