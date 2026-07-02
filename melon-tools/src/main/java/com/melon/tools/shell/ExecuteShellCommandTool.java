package com.melon.tools.shell;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.util.PlatformUtil;
import com.melon.core.util.WorkspacePathResolver;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具. 对应 Python shell.py:execute_shell_command.
 * 跨平台: Windows cmd /D /S /C, Unix sh -c.
 * 自杀防护: 检测 taskkill/kill 针对当前进程.
 */
public class ExecuteShellCommandTool extends ToolBase {

    private final WorkspacePathResolver pathResolver;

    public ExecuteShellCommandTool() {
        this(null);
    }

    public ExecuteShellCommandTool(String workspaceDir) {
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

        return Mono.fromCallable(() -> executeCommand(command, timeout, cwd))
                .map(result -> ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name("execute_shell_command")
                        .output(java.util.List.of(io.agentscope.core.message.TextBlock.builder().text(result).build()))
                        .build())
                .onErrorResume(e -> Mono.just(error(param, "Error: " + e.getMessage())));
    }

    private String executeCommand(String command, double timeout, String cwd) throws Exception {
        boolean isWindows = PlatformUtil.isWindows();

        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder("cmd", "/D", "/S", "/C", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }

        if (cwd != null) {
            pb.directory(pathResolver.resolve(cwd).toFile());
        } else {
            pb.directory(pathResolver.resolveOptional(null).toFile());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();

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

        boolean finished = process.waitFor((long) (timeout * 1000), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            output.append("\n[Command timed out after ").append(timeout).append("s]");
        }
        outputReader.join(1000);

        return output.toString();
    }

    private double normalizeTimeout(Object raw) {
        double value = raw instanceof Number number ? number.doubleValue() : 60.0;
        if (Double.isNaN(value) || value <= 0) {
            return 60.0;
        }
        return Math.min(value, 600.0);
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

    private static final java.util.List<Pattern> DANGER_PATTERNS = java.util.List.of(
            Pattern.compile("taskkill\\s+/F\\s+/T\\s+/PID"),
            Pattern.compile("kill\\s+-9\\s+\\$\\$"),
            Pattern.compile("stop-process\\s+-id\\s+\\$PID")
    );
}
