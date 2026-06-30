/**
 * @author melon
 */
package com.melon.app.mcp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    @PostConstruct
    public void init() {
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
                // stdio: 启动子进程 (近似)
                log.info("  command: {} {}", config.getCommand(), config.getArgs());
            } else if ("sse".equals(config.getTransport())) {
                // SSE: 建立 HTTP 连接 (近似)
                log.info("  url: {}", config.getUrl());
            } else {
                log.error("Unknown transport: {}", config.getTransport());
                connectionStates.put(id, new ConnectionState(false, "Unknown transport"));
                return false;
            }

            // 模拟工具发现
            discoverTools(config);
            connectionStates.put(id, new ConnectionState(true, null));
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
            // 近似实现: 关闭连接/进程
            log.info("Disconnecting MCP server: id={}", id);
            connectionStates.put(id, new ConnectionState(false, null));
        }
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

        // 查找工具
        McpTool tool = config.getTools().stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            return Map.of("error", "Tool not found: " + toolName);
        }

        log.info("Calling MCP tool: server={}, tool={}, args={}", id, toolName, args != null ? args.size() : 0);

        // 近似实现: 返回占位结果
        // 实际实现: 通过 JSON-RPC 发送 tools/call 请求
        return Map.of(
                "status", "ok",
                "server", id,
                "tool", toolName,
                "result", "[MCP tool result placeholder - connect actual MCP SDK for real execution]"
        );
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
    private void discoverTools(McpServerConfig config) {
        // 近似: 不做实际发现, 工具列表由配置提供
        if (config.getTools() == null || config.getTools().isEmpty()) {
            config.setTools(new ArrayList<>());
        }
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
        private boolean enabled = true;
        private List<McpTool> tools = new ArrayList<>();

        public static McpServerConfig fromMap(Map<String, Object> body) {
            McpServerConfig config = new McpServerConfig();
            if (body.get("id") != null) config.setId((String) body.get("id"));
            if (body.get("name") != null) config.setName((String) body.get("name"));
            if (body.get("transport") != null) config.setTransport((String) body.get("transport"));
            if (body.get("command") != null) config.setCommand((String) body.get("command"));
            if (body.get("url") != null) config.setUrl((String) body.get("url"));
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
            if (inputSchema != null) m.put("input_schema", inputSchema);
            return m;
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
}
