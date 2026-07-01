package com.melon.tools.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal ACP subprocess bridge for delegate_external_agent.
 */
public class DelegateExternalAgentTool extends ToolBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong();
    private static final Map<String, Map<String, Object>> CONFIG = new ConcurrentHashMap<>();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private final Path workspaceDir;

    public static void setConfig(Map<String, Map<String, Object>> config) {
        CONFIG.clear();
        if (config != null) CONFIG.putAll(config);
    }

    public DelegateExternalAgentTool(Path workspaceDir) {
        super(ToolBase.builder()
                .name("delegate_external_agent")
                .description("Open, talk to, inspect, or close an external ACP agent session.")
                .inputSchema(schema())
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceDir = workspaceDir;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            Map<String, Object> input = param.getInput();
            String action = string(input.get("action"), "list").toLowerCase(Locale.ROOT);
            String runner = string(input.get("runner"), "");
            String message = string(input.get("message"), "");
            String cwd = string(input.get("cwd"), workspaceDir.toString());
            return Mono.just(ToolResultBlock.text(handle(action, runner, message, cwd)));
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.error("ACP execution error: " + e.getMessage()));
        }
    }

    private String handle(String action, String runner, String message, String cwd) throws Exception {
        return switch (action) {
            case "list" -> list();
            case "status" -> status(runner);
            case "start" -> start(runner, message.isBlank() ? "hi" : message, cwd, true);
            case "message" -> start(runner, message, cwd, false);
            case "close" -> close(runner);
            default -> "Error: unsupported action '" + action + "'";
        };
    }

    private String list() {
        List<String> enabled = CONFIG.entrySet().stream()
                .filter(e -> Boolean.TRUE.equals(e.getValue().get("enabled")))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return enabled.isEmpty()
                ? "No enabled ACP runners are currently configured."
                : "Available ACP runners: " + String.join(", ", enabled) + ".";
    }

    private String status(String runner) {
        if (runner == null || runner.isBlank()) {
            if (SESSIONS.isEmpty()) return "No ACP sessions are currently open.";
            return "Open ACP sessions: " + String.join(", ", SESSIONS.keySet()) + ".";
        }
        Session session = SESSIONS.get(runner);
        if (session == null || !session.process.isAlive()) return "ACP runner '" + runner + "' has no open session.";
        return "ACP runner '" + runner + "' is running. Session ID: " + session.sessionId;
    }

    private String start(String runner, String message, String cwd, boolean restart) throws Exception {
        if (runner == null || runner.isBlank()) return "Error: runner is required.";
        Map<String, Object> config = CONFIG.get(runner);
        if (config == null || !Boolean.TRUE.equals(config.get("enabled"))) {
            return "Error: runner '" + runner + "' is not available for ACP start.";
        }
        Session session = SESSIONS.get(runner);
        if (restart || session == null || !session.process.isAlive()) {
            if (session != null) session.close();
            session = Session.open(runner, config, cwd == null || cwd.isBlank() ? workspaceDir.toString() : cwd);
            SESSIONS.put(runner, session);
        }
        return session.prompt(message == null ? "" : message);
    }

    private String close(String runner) {
        if (runner == null || runner.isBlank()) return "Error: runner is required.";
        Session session = SESSIONS.remove(runner);
        if (session == null) return "ACP runner '" + runner + "' has no open session.";
        session.close();
        return "ACP runner '" + runner + "' session closed.";
    }

    private static Map<String, Object> schema() {
        try {
            return JSON.readValue("""
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["list","status","start","message","respond","close"]},
                      "runner":{"type":"string"},
                      "message":{"type":"string"},
                      "cwd":{"type":"string"},
                      "max_runtime":{"type":"number"}
                    },"required":["action"]}
                    """, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String string(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static final class Session implements AutoCloseable {
        private final Process process;
        private final BufferedReader stdout;
        private final BufferedWriter stdin;
        private final String sessionId;

        private Session(Process process, String sessionId) {
            this.process = process;
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.sessionId = sessionId;
        }

        static Session open(String runner, Map<String, Object> config, String cwd) throws Exception {
            String command = string(config.get("command"), "");
            if (command.isBlank()) throw new IOException("ACP runner '" + runner + "' command is empty");
            List<String> args = list(config.get("args"));
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(cwd)).redirectErrorStream(true);
            Object env = config.get("env");
            if (env instanceof Map<?, ?> map) {
                map.forEach((k, v) -> pb.environment().put(String.valueOf(k), String.valueOf(v)));
            }
            Session session = new Session(pb.start(), "");
            session.request("initialize", Map.of(
                    "protocolVersion", 1,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "qwenpaw-java", "version", "1.0.0")
            ));
            Object created = session.request("new_session", Map.of("cwd", cwd));
            String sessionId = UUID.randomUUID().toString();
            if (created instanceof Map<?, ?> map && map.get("session_id") != null) {
                sessionId = String.valueOf(map.get("session_id"));
            }
            return new Session(session.process, sessionId);
        }

        String prompt(String message) throws Exception {
            Object result = request("prompt", Map.of(
                    "session_id", sessionId,
                    "prompt", List.of(Map.of("type", "text", "text", message))
            ));
            return stringifyResult(result);
        }

        private Object request(String method, Map<String, Object> params) throws Exception {
            long id = IDS.incrementAndGet();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params);
            stdin.write(JSON.writeValueAsString(payload));
            stdin.newLine();
            stdin.flush();
            long deadline = System.currentTimeMillis() + 300_000;
            StringBuilder notifications = new StringBuilder();
            while (System.currentTimeMillis() < deadline) {
                String line = stdout.readLine();
                if (line == null) throw new EOFException("ACP runner exited");
                Map<?, ?> response;
                try {
                    response = JSON.readValue(line, Map.class);
                } catch (Exception ignored) {
                    notifications.append(line).append('\n');
                    continue;
                }
                if (String.valueOf(id).equals(String.valueOf(response.get("id")))) {
                    if (response.get("error") != null) throw new IOException(String.valueOf(response.get("error")));
                    Object result = response.get("result");
                    return notifications.isEmpty() ? result : notifications + stringifyResult(result);
                }
                notifications.append(stringifyResult(response)).append('\n');
            }
            throw new IOException("ACP request timed out: " + method);
        }

        private static String stringifyResult(Object result) throws IOException {
            if (result == null) return "";
            if (result instanceof String text) return text;
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        }

        private static List<String> list(Object value) {
            if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
            return List.of();
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
