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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Map<String, Boolean> shellEvasionChecks;
    private final Set<String> disabledRuleIds;
    private final Set<String> autoDeniedRuleIds;
    private final List<ToolGuardRule> customRules;

    public ExecuteShellCommandTool() {
        this(null, 60.0, null);
    }

    public ExecuteShellCommandTool(String workspaceDir) {
        this(workspaceDir, 60.0, null);
    }

    public ExecuteShellCommandTool(String workspaceDir, double defaultTimeoutSeconds, String shellExecutable) {
        this(workspaceDir, defaultTimeoutSeconds, shellExecutable, Map.of());
    }

    public ExecuteShellCommandTool(String workspaceDir, double defaultTimeoutSeconds, String shellExecutable,
                                   Map<String, Object> toolGuardConfig) {
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
        Map<String, Object> guardConfig = toolGuardConfig != null ? toolGuardConfig : Map.of();
        Object checks = guardConfig.containsKey("shell_evasion_checks")
                ? guardConfig.get("shell_evasion_checks")
                : guardConfig;
        this.shellEvasionChecks = normalizeShellEvasionChecks(mapValue(checks));
        this.disabledRuleIds = stringSet(guardConfig.get("disabled_rules"));
        this.autoDeniedRuleIds = stringSet(guardConfig.get("auto_denied_rules"));
        this.customRules = parseCustomRules(guardConfig.get("custom_rules"));
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
            return looksLikeQwenPawDangerousShellCommand(command, false);
        }
        if ("qwenpaw-auto-deny-shell".equalsIgnoreCase(rule)) {
            return looksLikeQwenPawDangerousShellCommand(command, true);
        }
        return false;
    }

    private boolean looksLikeDeleteCommand(String command) {
        String normalized = command == null ? "" : command.toLowerCase();
        return DELETE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private boolean looksLikeQwenPawDangerousShellCommand(String command, boolean autoDenyOnly) {
        if (command == null || command.strip().startsWith("#")) return false;
        return matchesToolGuardRules(BUILTIN_RULES, command, autoDenyOnly)
                || matchesToolGuardRules(customRules, command, autoDenyOnly)
                || (!autoDenyOnly && looksLikeEnabledShellEvasion(command));
    }

    private static Pattern shellPattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    public static List<Map<String, Object>> builtinRulePayloads() {
        return BUILTIN_RULES.stream().map(ToolGuardRule::payload).toList();
    }

    private boolean matchesToolGuardRules(List<ToolGuardRule> rules, String command, boolean autoDenyOnly) {
        for (ToolGuardRule rule : rules) {
            if (disabledRuleIds.contains(rule.id())) continue;
            if (autoDenyOnly && !autoDeniedRuleIds.contains(rule.id())) continue;
            if (rule.matches(command)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Set<String> stringSet(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return Set.of();
        Set<String> result = new HashSet<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                result.add(String.valueOf(value));
            }
        }
        return Set.copyOf(result);
    }

    private List<ToolGuardRule> parseCustomRules(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<ToolGuardRule> rules = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> map = mapValue(value);
            if (map.isEmpty()) continue;
            if (!stringList(map.get("tools")).isEmpty()
                    && !stringList(map.get("tools")).contains("execute_shell_command")) {
                continue;
            }
            List<String> patterns = stringList(map.get("patterns"));
            if (patterns.isEmpty()) continue;
            String id = string(map.get("id"), "custom_shell_rule_" + rules.size());
            rules.add(new ToolGuardRule(
                    id,
                    string(map.get("category"), "custom"),
                    string(map.get("severity"), "HIGH"),
                    patterns,
                    stringList(map.get("exclude_patterns")),
                    string(map.get("description"), ""),
                    string(map.get("remediation"), "")
            ));
        }
        return List.copyOf(rules);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null) result.add(String.valueOf(value));
        }
        return List.copyOf(result);
    }

    private String string(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }

    private Map<String, Boolean> normalizeShellEvasionChecks(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Boolean> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), booleanValue(value)));
        return Map.copyOf(result);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean evasionEnabled(String name) {
        return Boolean.TRUE.equals(shellEvasionChecks.get(name));
    }

    private boolean looksLikeEnabledShellEvasion(String command) {
        return (evasionEnabled("command_substitution") && hasCommandSubstitution(command))
                || (evasionEnabled("obfuscated_flags") && hasObfuscatedFlags(command))
                || (evasionEnabled("backslash_escaped_whitespace") && hasBackslashEscapedWhitespace(command))
                || (evasionEnabled("backslash_escaped_operators") && hasBackslashEscapedOperator(command))
                || (evasionEnabled("newlines") && hasSuspiciousNewline(command))
                || (evasionEnabled("comment_quote_desync") && hasCommentQuoteDesync(command))
                || (evasionEnabled("quoted_newline") && hasQuotedNewlineComment(command));
    }

    private boolean hasCommandSubstitution(String command) {
        QuoteState state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                state.feed(ch);
                continue;
            }
            if (ch == '`' && !state.inSingle) return true;
            state.feed(ch);
        }
        String unquoted = extractOutsideSingleQuotes(command);
        return QWENPAW_COMMAND_SUBSTITUTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(unquoted).find());
    }

    private static String extractOutsideSingleQuotes(String command) {
        QuoteState state = new QuoteState();
        StringBuilder out = new StringBuilder(command.length());
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            boolean wasSingle = state.inSingle;
            state.feed(ch);
            if (!wasSingle && !state.inSingle) out.append(ch);
        }
        return out.toString();
    }

    private boolean hasObfuscatedFlags(String command) {
        if (ANSI_C_QUOTE.matcher(command).find()
                || LOCALE_QUOTE.matcher(command).find()
                || EMPTY_SPECIAL_QUOTE_DASH.matcher(command).find()
                || EMPTY_QUOTE_DASH.matcher(command).find()) {
            return true;
        }
        QuoteState state = new QuoteState();
        char prev = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                state.feed(ch);
                prev = ch;
                continue;
            }
            if (!state.inAnyQuote() && (prev == ' ' || prev == '\t') && (ch == '\'' || ch == '"')) {
                int end = command.indexOf(ch, i + 1);
                if (end > i && QUOTED_FLAG_CONTENT.matcher(command.substring(i + 1, end)).find()) {
                    return true;
                }
            }
            state.feed(ch);
            prev = ch;
        }
        return false;
    }

    private boolean hasBackslashEscapedWhitespace(String command) {
        QuoteState state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                if (!state.inDouble && (ch == ' ' || ch == '\t')) return true;
                state.feed(ch);
                continue;
            }
            state.feed(ch);
        }
        return false;
    }

    private boolean hasBackslashEscapedOperator(String command) {
        QuoteState state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                if (!state.inDouble && SHELL_OPERATORS.indexOf(ch) >= 0) {
                    String prefix = command.substring(0, i + 1);
                    if (ch == ';' && FIND_EXEC_TERMINATOR.matcher(prefix).find()) {
                        state.feed(ch);
                        continue;
                    }
                    return true;
                }
                state.feed(ch);
                continue;
            }
            state.feed(ch);
        }
        return false;
    }

    private boolean hasSuspiciousNewline(String command) {
        if (looksLikeHeredoc(command)) return false;
        QuoteState state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                state.feed(ch);
                continue;
            }
            state.feed(ch);
            if (ch == '\r' && !state.inDouble) return true;
        }
        state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                state.feed(ch);
                continue;
            }
            state.feed(ch);
            if ((ch == '\n' || ch == '\r') && !state.inAnyQuote()
                    && !command.substring(i + 1).strip().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeHeredoc(String command) {
        String[] lines = command.split("\\R", -1);
        if (lines.length < 2) return false;
        for (int i = 0; i < lines.length; i++) {
            var matcher = HEREDOC_OPENER.matcher(lines[i]);
            if (!matcher.find()) continue;
            String delimiter = matcher.group(2);
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].strip().equals(delimiter)) return true;
            }
        }
        return false;
    }

    private boolean hasCommentQuoteDesync(String command) {
        if (command.indexOf('#') < 0) return false;
        QuoteState state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                state.feed(ch);
                continue;
            }
            state.feed(ch);
            if (ch == '#' && !state.inAnyQuote()) {
                int lineEnd = command.indexOf('\n', i);
                String comment = command.substring(i + 1, lineEnd >= 0 ? lineEnd : command.length());
                if (comment.indexOf('\'') >= 0 || comment.indexOf('"') >= 0) return true;
                if (lineEnd < 0) break;
                i = lineEnd;
            }
        }
        return false;
    }

    private boolean hasQuotedNewlineComment(String command) {
        if (command.indexOf('\n') < 0 || command.indexOf('#') < 0) return false;
        QuoteState state = new QuoteState();
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (state.escaped) {
                state.feed(ch);
                continue;
            }
            state.feed(ch);
            if (ch == '\n' && state.inAnyQuote()) {
                int lineEnd = command.indexOf('\n', i + 1);
                String nextLine = command.substring(i + 1, lineEnd >= 0 ? lineEnd : command.length());
                if (nextLine.strip().startsWith("#")) return true;
            }
        }
        return false;
    }

    private static final Pattern ANSI_C_QUOTE = Pattern.compile("\\$'[^']*'");
    private static final Pattern LOCALE_QUOTE = Pattern.compile("\\$\"[^\"]*\"");
    private static final Pattern EMPTY_SPECIAL_QUOTE_DASH = Pattern.compile("\\$['\"]{2}\\s*-");
    private static final Pattern EMPTY_QUOTE_DASH = Pattern.compile("(?:^|\\s)(?:''|\"\")+\\s*-");
    private static final Pattern QUOTED_FLAG_CONTENT = Pattern.compile("^-+[a-zA-Z0-9$`]");
    private static final Pattern FIND_EXEC_TERMINATOR = Pattern.compile("-(?:exec|execdir)\\b[\\s\\S]*\\{\\}\\s*\\\\;$");
    private static final Pattern HEREDOC_OPENER = Pattern.compile("<<-?\\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\\1");
    private static final String SHELL_OPERATORS = ";|&<>";

    private static final java.util.List<Pattern> QWENPAW_COMMAND_SUBSTITUTION_PATTERNS = java.util.List.of(
            Pattern.compile("<\\("),
            Pattern.compile(">\\("),
            Pattern.compile("=\\("),
            Pattern.compile("(?:^|[\\s;&|])=[a-zA-Z_]"),
            Pattern.compile("\\$\\("),
            Pattern.compile("\\$\\["),
            Pattern.compile("~\\["),
            Pattern.compile("\\(e:"),
            Pattern.compile("\\(\\+"),
            Pattern.compile("\\}\\s*always\\s*\\{"),
            Pattern.compile("<#")
    );

    private static final class QuoteState {
        private boolean inSingle;
        private boolean inDouble;
        private boolean escaped;

        private boolean inAnyQuote() {
            return inSingle || inDouble;
        }

        private void feed(char ch) {
            if (escaped) {
                escaped = false;
                return;
            }
            if (ch == '\\' && !inSingle) {
                escaped = true;
                return;
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                return;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            }
        }
    }

    private record ToolGuardRule(
            String id,
            String category,
            String severity,
            List<Pattern> patterns,
            List<Pattern> excludePatterns,
            List<String> patternText,
            List<String> excludePatternText,
            String description,
            String remediation) {

        private ToolGuardRule(String id, String category, String severity, List<String> patterns,
                              List<String> excludePatterns, String description, String remediation) {
            this(id, category, severity,
                    patterns.stream().map(ExecuteShellCommandTool::shellPattern).toList(),
                    excludePatterns.stream().map(ExecuteShellCommandTool::shellPattern).toList(),
                    List.copyOf(patterns),
                    List.copyOf(excludePatterns),
                    description,
                    remediation);
        }

        private boolean matches(String command) {
            return patterns.stream().anyMatch(pattern -> pattern.matcher(command).find())
                    && excludePatterns.stream().noneMatch(pattern -> pattern.matcher(command).find());
        }

        private Map<String, Object> payload() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("tools", List.of("execute_shell_command"));
            map.put("params", List.of("command"));
            map.put("category", category);
            map.put("severity", severity);
            map.put("patterns", patternText);
            map.put("exclude_patterns", excludePatternText);
            map.put("description", description);
            map.put("remediation", remediation);
            return map;
        }
    }

    private static ToolGuardRule builtinRule(String id, String category, String severity, List<String> patterns,
                                             List<String> excludePatterns, String description, String remediation) {
        return new ToolGuardRule(id, category, severity, patterns, excludePatterns, description, remediation);
    }

    private static final java.util.List<ToolGuardRule> BUILTIN_RULES = java.util.List.of(
            builtinRule("TOOL_CMD_DANGEROUS_RM", "command_injection", "HIGH",
                    List.of("\\brm\\b", "\\bdel\\b", "\\bRemove-Item\\b"),
                    List.of("^\\s*#"),
                    "Shell command contains 'rm' which may cause data loss",
                    "Confirm with the user before removing files or directories"),
            builtinRule("TOOL_CMD_DANGEROUS_MV", "command_injection", "HIGH",
                    List.of("\\bmv\\b"),
                    List.of(),
                    "Shell command contains 'mv' which may move or overwrite files unexpectedly",
                    "Confirm with the user before moving or renaming files"),
            builtinRule("TOOL_CMD_FS_DESTRUCTION", "command_injection", "CRITICAL",
                    List.of("\\bmkfs(\\.[a-zA-Z0-9_]+)?\\b", "\\bmke2fs\\b", "\\bdd\\s+.*of=\\/dev\\/",
                            ">\\s*\\/dev\\/(sd[a-z][0-9]*|vd[a-z][0-9]*|nvme\\d+n\\d+(p\\d+)?)"),
                    List.of(),
                    "Detects low-level disk formatting or wiping commands",
                    "Block operation. Agents should not format or overwrite raw block devices."),
            builtinRule("TOOL_CMD_DOS_FORK_BOMB", "resource_abuse", "CRITICAL",
                    List.of(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:",
                            "\\bkill\\s+-9\\s+(-1\\b|1\\b)"),
                    List.of(),
                    "Detects classic Bash fork bombs and mass process termination",
                    "Block immediately. These commands will crash the host system."),
            builtinRule("TOOL_CMD_PIPE_TO_SHELL", "code_execution", "CRITICAL",
                    List.of("\\b(curl|wget)\\b\\s+.*\\|.*\\b(bash|sh|zsh|ash|dash)\\b"),
                    List.of(),
                    "Detects 'curl | bash' patterns used to download and immediately execute remote payloads",
                    "Confirm with user. Agents should inspect scripts before executing them."),
            builtinRule("TOOL_CMD_REVERSE_SHELL", "network_abuse", "CRITICAL",
                    List.of("\\/dev\\/(tcp|udp)\\/", "\\bnc\\s+.*-e\\s*\\S+", "\\bncat\\s+.*-e\\s*\\S+",
                            "\\bsocat\\s+.*EXEC:"),
                    List.of(),
                    "Detects attempts to establish reverse shells or unauthorized network tunnels",
                    "Block operation. Agents do not need to bind interactive shells to network sockets."),
            builtinRule("TOOL_CMD_SYSTEM_TAMPERING", "sensitive_file_access", "HIGH",
                    List.of("\\bcrontab\\b", "\\bauthorized_keys\\b", "\\/etc\\/sudoers", "\\/etc\\/crontab"),
                    List.of(),
                    "Detects access to cron jobs, SSH keys, or sudo permissions (including reads and modifications)",
                    "Confirm with user. Treat any access to credential and scheduling files as sensitive and restrict when possible."),
            builtinRule("TOOL_CMD_UNSAFE_PERMISSIONS", "privilege_escalation", "HIGH",
                    List.of("\\bchmod\\s+-[a-zA-Z]*R[a-zA-Z]*\\s+(777|a\\+rwx)\\s+\\/", "\\bchattr\\s+\\+i"),
                    List.of(),
                    "Detects global permission downgrades (chmod 777) or setting immutable flags",
                    "Prompt for confirmation. Suggest least-privilege permission models."),
            builtinRule("TOOL_CMD_OBFUSCATED_EXEC", "code_execution", "HIGH",
                    List.of("\\bbase64\\s+(-d|--decode)\\s*\\|\\s*\\b(bash|sh|zsh)\\b"),
                    List.of(),
                    "Detects execution of base64 encoded strings passed directly to a shell interpreter",
                    "Block execution. Agents should use plain text commands."),
            builtinRule("TOOL_CMD_SYSTEM_REBOOT", "resource_abuse", "CRITICAL",
                    List.of("\\b(reboot|shutdown|halt|poweroff)\\b", "\\binit\\s+(0|6)\\b", "\\btelinit\\s+(0|6)\\b",
                            "\\bShutdown-Computer\\b", "\\bRestart-Computer\\b"),
                    List.of(),
                    "Detects system reboot or shutdown commands that will terminate the host system",
                    "Block operation. Agents should not restart or shutdown the system."),
            builtinRule("TOOL_CMD_SERVICE_RESTART", "resource_abuse", "HIGH",
                    List.of("\\bsystemctl\\s+(restart|stop|start|reload|kill)\\b",
                            "\\bservice\\s+\\S+\\s+(restart|stop|start|reload)\\b",
                            "\\b(sc|net)\\s+(start|stop|restart)\\b",
                            "\\blaunchctl\\s+(load|unload|stop|start|kickstart|kill)\\b",
                            "\\brc-service\\s+(restart|stop|start)\\b"),
                    List.of(),
                    "Detects service management commands that can disrupt system services",
                    "Confirm with user. Restarting services may cause downtime or data loss."),
            builtinRule("TOOL_CMD_PROCESS_KILL", "resource_abuse", "HIGH",
                    List.of("\\b(pkill|killall)\\b", "\\bkill\\s+(-(9|KILL|15|TERM|1|HUP|2|INT)\\s+)?[^-\\s]",
                            "\\btaskkill\\s+\\/F\\b", "\\bStop-Process\\b.*-Force\\b"),
                    List.of("kill\\s+\\$\\$"),
                    "Detects process termination commands that may kill critical processes",
                    "Confirm with user. Killing processes may cause data loss or system instability."),
            builtinRule("TOOL_CMD_PRIVILEGE_ESCALATION", "privilege_escalation", "CRITICAL",
                    List.of("\\bsudo\\s+", "\\bsu\\b", "\\bdoas\\s+", "\\bpkexec\\b", "\\brunas\\s+\\/user:"),
                    List.of("^\\s*#"),
                    "Detects privilege escalation attempts using sudo, su, doas, pkexec, or runas",
                    "Block operation. Agents should not execute commands with elevated privileges."),
            builtinRule("TOOL_CMD_IFS_INJECTION", "code_execution", "HIGH",
                    List.of("\\$IFS(?![A-Za-z0-9_])", "\\$\\{[^}]*IFS"),
                    List.of("^\\s*#"),
                    "Command uses $IFS variable which could bypass security validation",
                    "Reject commands containing IFS manipulation. Legitimate commands do not need to reference $IFS directly."),
            builtinRule("TOOL_CMD_CONTROL_CHARS", "code_execution", "CRITICAL",
                    List.of("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]"),
                    List.of(),
                    "Command contains non-printable control characters that could bypass security checks",
                    "Block commands containing control characters. These are never needed in legitimate shell commands."),
            builtinRule("TOOL_CMD_UNICODE_WHITESPACE", "code_execution", "HIGH",
                    List.of("[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"),
                    List.of(),
                    "Command contains Unicode whitespace characters that could cause parsing inconsistencies",
                    "Block commands containing non-ASCII whitespace. Use standard spaces instead."),
            builtinRule("TOOL_CMD_PROC_ENVIRON", "sensitive_file_access", "HIGH",
                    List.of("\\/proc\\/(?:self|\\d+)\\/environ(?:\\b|$)"),
                    List.of("^\\s*#"),
                    "Command accesses /proc/*/environ which could expose sensitive environment variables",
                    "Block access to process environment files. API keys and secrets may be exposed."),
            builtinRule("TOOL_CMD_JQ_SYSTEM", "code_execution", "HIGH",
                    List.of("\\bjq\\b.*\\bsystem\\s*\\("),
                    List.of("^\\s*#"),
                    "jq command contains system() function which can execute arbitrary shell commands",
                    "Block jq commands using system(). Use jq only for data processing, not command execution."),
            builtinRule("TOOL_CMD_JQ_FILE_FLAGS", "code_execution", "HIGH",
                    List.of("\\bjq\\b.*(?:\\s-f\\b|\\s--from-file\\b|\\s--rawfile\\b|\\s--slurpfile\\b|\\s-L\\b|\\s--library-path\\b)"),
                    List.of("^\\s*#"),
                    "jq command uses flags that could read arbitrary files or execute external code",
                    "Confirm with user. These jq flags can access files outside the intended scope."),
            builtinRule("TOOL_CMD_ZSH_DANGEROUS", "code_execution", "HIGH",
                    List.of("\\bzmodload\\b", "\\bemulate\\b(?:\\s+-\\S+)*\\s+-c\\b",
                            "\\b(sysopen|sysread|syswrite|sysseek)\\b", "\\b(zpty|ztcp|zsocket)\\b",
                            "\\bzf_(rm|mv|ln|chmod|chown|mkdir|rmdir|chgrp)\\b", "\\bfc\\b.*\\s-\\S*e"),
                    List.of("^\\s*#"),
                    "Command uses Zsh-specific builtins that can bypass security checks",
                    "Block Zsh module/builtin commands. These provide raw file I/O, network, and execution capabilities that bypass normal permission checks.")
    );

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

}
