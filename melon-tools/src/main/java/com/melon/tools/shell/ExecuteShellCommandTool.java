/**
 * @author melon
 */
package com.melon.tools.shell;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具. 对应 Python shell.py:execute_shell_command.
 * 跨平台: Windows cmd /D /S /C, Unix sh -c.
 * 自杀防护: 检测 taskkill/kill 针对当前进程.
 */
public class ExecuteShellCommandTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteShellCommandTool.class);

    public ExecuteShellCommandTool() {
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
                            }
                          },
                          "required": ["command"]
                        }"""))
                .readOnly(false)
                .concurrencySafe(false));
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
        double timeout = ((Number) param.getInput().getOrDefault("timeout", 60.0)).doubleValue();
        String cwd = (String) param.getInput().get("cwd");

        // 自杀防护
        if (isDangerousSelfKill(command)) {
            return Mono.just(ToolResultBlock.builder()
                    .id(param.getToolUseBlock().getId())
                    .name("execute_shell_command")
                    .output(java.util.List.of(io.agentscope.core.message.TextBlock.builder().text("Error: Self-kill command detected and blocked.").build()))
                    .state(io.agentscope.core.message.ToolResultState.ERROR)
                    .build());
        }

        return Mono.fromCallable(() -> executeCommand(command, timeout, cwd))
                .map(result -> ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name("execute_shell_command")
                        .output(java.util.List.of(io.agentscope.core.message.TextBlock.builder().text(result).build()))
                        .build())
                .onErrorResume(e -> Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name("execute_shell_command")
                        .output(java.util.List.of(io.agentscope.core.message.TextBlock.builder().text("Error: " + e.getMessage()).build()))
                        .state(io.agentscope.core.message.ToolResultState.ERROR)
                        .build()));
    }

    private String executeCommand(String command, double timeout, String cwd) throws Exception {
        String shell = PlatformUtil.getDefaultShell();
        boolean isWindows = PlatformUtil.isWindows();

        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder("cmd", "/D", "/S", "/C", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }

        if (cwd != null) {
            pb.directory(Path.of(cwd).toFile());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor((long) (timeout * 1000), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            output.append("\n[Command timed out after ").append(timeout).append("s]");
        }

        return output.toString();
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
