package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpClientManagerSelfCheck {

    private McpClientManagerSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger authedMcpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("grant_type=client_credentials") || !body.contains("client_id=client")) {
                respond(exchange, 400, "{\"error\":\"bad form\"}");
                return;
            }
            respond(exchange, 200, "{\"access_token\":\"token-abc\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        server.createContext("/mcp", exchange -> {
            if (!"Bearer token-abc".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{\"error\":\"missing auth\"}");
                return;
            }
            authedMcpCalls.incrementAndGet();
            @SuppressWarnings("unchecked")
            Map<String, Object> request = JsonUtils.getMapper().readValue(exchange.getRequestBody(), LinkedHashMap.class);
            String method = String.valueOf(request.get("method"));
            if ("notifications/initialized".equals(method)) {
                respond(exchange, 202, "");
                return;
            }
            Object id = request.get("id");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            if ("initialize".equals(method)) {
                response.put("result", Map.of("capabilities", Map.of()));
            } else if ("tools/list".equals(method)) {
                response.put("result", Map.of("tools", List.of(Map.of(
                        "name", "echo",
                        "description", "Echo input",
                        "inputSchema", Map.of("type", "object")
                ))));
            } else if ("tools/call".equals(method)) {
                response.put("result", Map.of("content", List.of(Map.of("type", "text", "text", "ok"))));
            } else {
                response.put("error", Map.of("message", "unknown method"));
            }
            respond(exchange, 200, JsonUtils.toJson(response));
        });
        server.start();
        try {
            Path home = Files.createTempDirectory("melon-mcp-check");
            ConfigManager configManager = new ConfigManager();
            configManager.setHomeDir(home.toString());
            McpClientManager manager = new McpClientManager(configManager);
            int port = server.getAddress().getPort();

            McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                    "id", "remote",
                    "name", "remote",
                    "transport", "streamable_http",
                    "url", "http://127.0.0.1:" + port + "/mcp",
                    "oauth", Map.of(
                            "token_url", "http://127.0.0.1:" + port + "/token",
                            "grant_type", "client_credentials",
                            "client_id", "client",
                            "client_secret", "secret"
                    )
            ));
            manager.register(config);
            if (!manager.getConnectionState("remote").connected()) {
                throw new AssertionError("remote MCP did not connect");
            }
            if (manager.listTools("remote").size() != 1) {
                throw new AssertionError("remote MCP tools/list failed");
            }
            Map<String, Object> result = manager.callTool("remote", "echo", Map.of("text", "hello"));
            if (!result.containsKey("content")) {
                throw new AssertionError("remote MCP tools/call failed: " + result);
            }
            if (tokenCalls.get() != 1) {
                throw new AssertionError("OAuth token was not cached: " + tokenCalls.get());
            }
            if (authedMcpCalls.get() < 3) {
                throw new AssertionError("Authorization header was not injected");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
