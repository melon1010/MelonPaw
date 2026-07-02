package com.melon.app.mcp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.melon.core.config.ConfigManager;
import com.melon.core.env.EnvBridge;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) 客户端管理器.
 * <p>
 * 管理 MCP 客户端的生命周期: 注册/连接/断开/重载.
 * 支持 stdio 和 SSE 两种传输方式.
 * <p>
 * 注意: 当前为近似实现, 不包含实际的 JSON-RPC 通信.
 * 实际 MCP SDK 集成后, connect/disconnect/callTool 将委托给 SDK.
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** 已注册的 MCP 服务器配置. */
    private final ConcurrentHashMap<String, McpServerConfig> servers = new ConcurrentHashMap<>();
    /** 服务器连接状态. */
    private final ConcurrentHashMap<String, ConnectionState> connectionStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StdioSession> stdioSessions = new ConcurrentHashMap<>();
    private final ConfigManager configManager;

    public McpClientManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @PostConstruct
    public void init() {
        loadPersisted();
        for (McpServerConfig config : servers.values()) {
            if (config.isEnabled()) {
                connect(config.getId());
            }
        }
        log.info("McpClientManager initialized");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down McpClientManager, disconnecting {} servers", servers.size());
        for (String id : servers.keySet()) {
            disconnect(id);
        }
    }

    // ======================== Server Management ========================

    /**
     * 注册一个 MCP 服务器.
     *
     * @param config 服务器配置
     * @return 注册后的配置 (含生成的 id)
     */
    public McpServerConfig register(McpServerConfig config) {
        if (config.getId() == null || config.getId().isBlank()) {
            config.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        if (config.getName() == null || config.getName().isBlank()) {
            config.setName("mcp-" + config.getId());
        }
        servers.put(config.getId(), config);
        connectionStates.put(config.getId(), new ConnectionState(false, null));
        persist();

        if (config.isEnabled()) {
            connect(config.getId());
        }
        log.info("MCP server registered: id={}, name={}, transport={}",
                config.getId(), config.getName(), config.getTransport());
        return config;
    }

    /**
     * 移除一个 MCP 服务器.
     */
    public boolean unregister(String id) {
        McpServerConfig removed = servers.remove(id);
        if (removed == null) {
            return false;
        }
        disconnect(id);
        connectionStates.remove(id);
        persist();
        log.info("MCP server unregistered: id={}", id);
        return true;
    }

    /**
     * 列出所有 MCP 服务器.
     */
    public List<McpServerConfig> listServers() {
        return new ArrayList<>(servers.values());
    }

    /**
     * 获取指定服务器配置.
     */
    public McpServerConfig getServer(String id) {
        return servers.get(id);
    }

    // ======================== Connection Lifecycle ========================

    /**
     * 连接到 MCP 服务器.
     */
    public boolean connect(String id) {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            log.warn("MCP server not found: id={}", id);
            return false;
        }
        ConnectionState state = connectionStates.get(id);
        if (state != null && state.connected()) {
            log.info("MCP server already connected: id={}", id);
            return true;
        }

        try {
            // 近似实现: 模拟连接过程
            // 实际实现: 根据 transport 类型启动 stdio 进程或建立 SSE 连接
            log.info("Connecting to MCP server: id={}, transport={}", id, config.getTransport());

            if ("stdio".equals(config.getTransport())) {
                log.info("  command: {} {}", config.getCommand(), config.getArgs());
                StdioSession session = StdioSession.start(config);
                stdioSessions.put(id, session);
                config.setTools(new ArrayList<>(session.listTools().stream().map(McpTool::fromMap).toList()));
            } else if ("sse".equals(config.getTransport()) || "streamable_http".equals(config.getTransport())) {
                connectionStates.put(id, new ConnectionState(false, "Only stdio MCP is implemented"));
                return false;
            } else {
                log.error("Unknown transport: {}", config.getTransport());
                connectionStates.put(id, new ConnectionState(false, "Unknown transport"));
                return false;
            }
            connectionStates.put(id, new ConnectionState(true, null));
            persist();
            log.info("MCP server connected: id={}, tools={}", id, config.getTools().size());
            return true;

        } catch (Exception e) {
            log.error("Failed to connect MCP server: id={}", id, e);
            connectionStates.put(id, new ConnectionState(false, e.getMessage()));
            return false;
        }
    }

    /**
     * 断开 MCP 服务器连接.
     */
    public void disconnect(String id) {
        ConnectionState state = connectionStates.get(id);
        if (state != null && state.connected()) {
            log.info("Disconnecting MCP server: id={}", id);
        }
        StdioSession session = stdioSessions.remove(id);
        if (session != null) session.close();
        connectionStates.put(id, new ConnectionState(false, null));
    }

    /**
     * 热重载: 断开所有服务器, 重新连接所有已启用的服务器.
     */
    public void reload() {
        log.info("Hot-reloading MCP servers: {} total", servers.size());
        for (String id : servers.keySet()) {
            disconnect(id);
        }
        for (McpServerConfig config : servers.values()) {
            if (config.isEnabled()) {
                connect(config.getId());
            }
        }
        log.info("MCP servers reloaded");
    }

    /**
     * 获取连接状态.
     */
    public ConnectionState getConnectionState(String id) {
        return connectionStates.getOrDefault(id, new ConnectionState(false, "not registered"));
    }

    // ======================== Tool Operations ========================

    /**
     * 列出服务器的可用工具.
     */
    public List<McpTool> listTools(String id) {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            return List.of();
        }
        return config.getTools();
    }

    /**
     * 调用 MCP 服务器上的工具.
     *
     * @param id       服务器 ID
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具执行结果
     */
    public Map<String, Object> callTool(String id, String toolName, Map<String, Object> args) {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            return Map.of("error", "MCP server not found: " + id);
        }
        ConnectionState state = connectionStates.get(id);
        if (state == null || !state.connected()) {
            return Map.of("error", "MCP server not connected: " + id);
        }

        McpTool tool = config.getTools().stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            return Map.of("error", "Tool not found: " + toolName);
        }

        log.info("Calling MCP tool: server={}, tool={}, args={}", id, toolName, args != null ? args.size() : 0);

        StdioSession session = stdioSessions.get(id);
        if (session == null || !session.isAlive()) {
            if (!connect(id)) {
                return Map.of("error", "MCP server not connected: " + id);
            }
            session = stdioSessions.get(id);
        }
        try {
            return session.callTool(toolName, args == null ? Map.of() : args);
        } catch (Exception e) {
            log.error("MCP tool call failed: server={}, tool={}", id, toolName, e);
            connectionStates.put(id, new ConnectionState(false, e.getMessage()));
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * 获取所有已连接服务器的全部工具.
     */
    public List<Map<String, Object>> listAllTools() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (McpServerConfig config : servers.values()) {
            ConnectionState state = connectionStates.get(config.getId());
            if (state != null && state.connected()) {
                for (McpTool tool : config.getTools()) {
                    all.add(Map.of(
                            "server_id", config.getId(),
                            "server_name", config.getName(),
                            "tool_name", tool.getName(),
                            "description", tool.getDescription() != null ? tool.getDescription() : ""
                    ));
                }
            }
        }
        return all;
    }

    // ======================== Internal ========================

    /**
     * 模拟工具发现. 实际实现通过 JSON-RPC tools/list 请求.
     */
    private void loadPersisted() {
        Map<String, Object> payload = JsonUtils.loadAsMap(configFile());
        Object rawServers = payload.get("servers");
        if (rawServers instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    map.forEach((k, v) -> values.put(String.valueOf(k), v));
                    McpServerConfig config = McpServerConfig.fromMap(values);
                    if (config.getId() != null && !config.getId().isBlank()) {
                        servers.put(config.getId(), config);
                        connectionStates.put(config.getId(), new ConnectionState(false, null));
                    }
                }
            }
        }
    }

    private void persist() {
        JsonUtils.save(configFile(), Map.of("servers", servers.values().stream().map(McpServerConfig::toMap).toList()));
    }

    private Path configFile() {
        return configManager.resolveStateDir().resolve("mcp-clients.json");
    }

    // ======================== Data Models ========================

    /**
     * MCP 服务器配置.
     */
    public static class McpServerConfig {
        private String id;
        private String name;
        private String transport = "stdio";  // stdio / sse
        private String command;              // stdio: 启动命令
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private String url;                  // sse: 服务器 URL
        private String cwd;
        private Map<String, String> headers = new HashMap<>();
        private boolean enabled = true;
        private List<McpTool> tools = new ArrayList<>();

        public static McpServerConfig fromMap(Map<String, Object> body) {
            McpServerConfig config = new McpServerConfig();
            if (body.get("id") != null) config.setId((String) body.get("id"));
            if (body.get("name") != null) config.setName((String) body.get("name"));
            if (body.get("transport") != null) config.setTransport((String) body.get("transport"));
            if (body.get("command") != null) config.setCommand((String) body.get("command"));
            if (body.get("url") != null) config.setUrl((String) body.get("url"));
            if (body.get("cwd") != null) config.setCwd((String) body.get("cwd"));
            if (body.get("enabled") != null) config.setEnabled((Boolean) body.get("enabled"));
            if (body.get("args") instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> argsList = (List<String>) body.get("args");
                config.setArgs(argsList);
            }
            if (body.get("env") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> envMap = (Map<String, String>) body.get("env");
                config.setEnv(envMap);
            }
            if (body.get("headers") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> headerMap = (Map<String, String>) body.get("headers");
                config.setHeaders(headerMap);
            }
            if (body.get("tools") instanceof List<?> list) {
                List<McpTool> tools = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        map.forEach((k, v) -> values.put(String.valueOf(k), v));
                        tools.add(McpTool.fromMap(values));
                    }
                }
                config.setTools(tools);
            }
            return config;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("transport", transport);
            m.put("command", command);
            m.put("args", args);
            m.put("env", env);
            m.put("url", url);
            m.put("cwd", cwd);
            m.put("headers", headers);
            m.put("enabled", enabled);
            m.put("tools", tools.stream().map(McpTool::toMap).toList());
            return m;
        }

        // getters/setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers == null ? new HashMap<>() : headers; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<McpTool> getTools() { return tools; }
        public void setTools(List<McpTool> tools) { this.tools = tools; }
    }

    /**
     * MCP 工具定义.
     */
    public static class McpTool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;

        public McpTool() {}

        public McpTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description != null ? description : "");
            m.put("input_schema", inputSchema != null ? inputSchema : Map.of("type", "object", "properties", Map.of()));
            return m;
        }

        public static McpTool fromMap(Map<String, Object> map) {
            McpTool tool = new McpTool();
            tool.setName(String.valueOf(map.getOrDefault("name", "")));
            tool.setDescription(String.valueOf(map.getOrDefault("description", "")));
            Object schema = map.get("input_schema");
            if (schema == null) schema = map.get("inputSchema");
            if (schema instanceof Map<?, ?> raw) {
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                tool.setInputSchema(values);
            }
            return tool;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
        public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
    }

    /**
     * 连接状态.
     */
    public record ConnectionState(boolean connected, String error) {}

    private static final class StdioSession implements AutoCloseable {
        private static final AtomicLong IDS = new AtomicLong();
        private final Process process;
        private final BufferedWriter stdin;
        private final InputStream stdout;
        private final Object lock = new Object();

        private StdioSession(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = process.getInputStream();
        }

        static StdioSession start(McpServerConfig config) throws IOException {
            if (config.getCommand() == null || config.getCommand().isBlank()) {
                throw new IOException("stdio MCP command is required");
            }
            List<String> command = new ArrayList<>();
            command.add(config.getCommand());
            command.addAll(config.getArgs() == null ? List.of() : config.getArgs());
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
            if (config.getCwd() != null && !config.getCwd().isBlank()) {
                pb.directory(new File(config.getCwd()));
            }
            EnvBridge.applyToProcessEnv(pb.environment());
            if (config.getEnv() != null) pb.environment().putAll(config.getEnv());
            Process process = pb.start();
            Thread stderr = new Thread(() -> {
                try (InputStream err = process.getErrorStream()) {
                    err.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            }, "mcp-stderr-" + config.getId());
            stderr.setDaemon(true);
            stderr.start();
            StdioSession session = new StdioSession(process);
            session.initialize();
            return session;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void initialize() throws IOException {
            request("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "qwenpaw-java", "version", "1.0.0")
            ));
            notify("notifications/initialized", Map.of());
        }

        List<Map<String, Object>> listTools() throws IOException {
            Object result = request("tools/list", Map.of());
            if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
                List<Map<String, Object>> tools = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                        Object inputSchema = values.remove("inputSchema");
                        if (inputSchema != null && !values.containsKey("input_schema")) {
                            values.put("input_schema", inputSchema);
                        }
                        tools.add(values);
                    }
                }
                return tools;
            }
            return List.of();
        }

        Map<String, Object> callTool(String name, Map<String, Object> arguments) throws IOException {
            Object result = request("tools/call", Map.of("name", name, "arguments", arguments));
            if (result instanceof Map<?, ?> raw) {
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                return values;
            }
            return Map.of("result", result);
        }

        private Object request(String method, Map<String, Object> params) throws IOException {
            long id = IDS.incrementAndGet();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params);
            synchronized (lock) {
                write(payload);
                while (true) {
                    Map<String, Object> response = readMessage();
                    if (response == null) throw new EOFException("MCP server closed stdout");
                    Object responseId = response.get("id");
                    if (responseId == null || !String.valueOf(responseId).equals(String.valueOf(id))) {
                        continue;
                    }
                    if (response.get("error") != null) {
                        throw new IOException(String.valueOf(response.get("error")));
                    }
                    return response.get("result");
                }
            }
        }

        private void notify(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            payload.put("params", params);
            synchronized (lock) {
                write(payload);
            }
        }

        private void write(Map<String, Object> payload) throws IOException {
            String json = JsonUtils.toJson(payload);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            stdin.write("Content-Length: " + body.length + "\r\n\r\n");
            stdin.write(json);
            stdin.flush();
        }

        private Map<String, Object> readMessage() throws IOException {
            int contentLength = -1;
            String line;
            while ((line = readAsciiLine(stdout)) != null) {
                if (line.isEmpty()) break;
                int idx = line.indexOf(':');
                if (idx > 0 && "content-length".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    contentLength = Integer.parseInt(line.substring(idx + 1).trim());
                }
            }
            if (line == null) return null;
            if (contentLength < 0) throw new IOException("MCP response missing Content-Length");
            byte[] body = stdout.readNBytes(contentLength);
            if (body.length != contentLength) throw new EOFException("MCP response body truncated");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JsonUtils.getMapper().readValue(body, LinkedHashMap.class);
            return map;
        }

        private static String readAsciiLine(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') buffer.write(b);
            }
            if (b == -1 && buffer.size() == 0) return null;
            return buffer.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
