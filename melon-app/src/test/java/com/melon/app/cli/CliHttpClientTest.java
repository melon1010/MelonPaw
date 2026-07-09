package com.melon.app.cli;

import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.context.CliOptions;
import com.melon.app.cli.http.CliHttpClient;
import com.melon.app.cli.http.CliHttpResponse;
import com.melon.app.cli.spec.CliCommandSpecs;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliHttpClientTest {

    @Test
    void sendsRequestsFromSpecs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/envs/TEST_KEY", exchange -> {
            assertEquals("PUT", exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("value"));
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            CliOptions options = new CliOptions();
            set(options, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            set(options, "host", "127.0.0.1");
            set(options, "port", 8088);
            set(options, "profile", "default");
            set(options, "output", com.melon.app.cli.context.CliOutputFormat.PLAIN);
            set(options, "timeoutSeconds", 5);
            CliHttpResponse response = new CliHttpClient().execute(
                    CliContext.from(options),
                    CliCommandSpecs.ENV_SET,
                    Map.of("key", "TEST_KEY"),
                    Map.of("value", "123"));
            assertEquals(200, response.statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsPatchRequestsFromSpecs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agents/demo/toggle", exchange -> {
            assertEquals("PATCH", exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("enabled"));
            byte[] bytes = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            CliOptions options = new CliOptions();
            set(options, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            set(options, "host", "127.0.0.1");
            set(options, "port", 8088);
            set(options, "profile", "default");
            set(options, "output", com.melon.app.cli.context.CliOutputFormat.PLAIN);
            set(options, "timeoutSeconds", 5);
            CliHttpResponse response = new CliHttpClient().execute(
                    CliContext.from(options),
                    CliCommandSpecs.AGENTS_TOGGLE,
                    Map.of("id", "demo"),
                    Map.of("enabled", true));
            assertEquals(200, response.statusCode());
        } finally {
            server.stop(0);
        }
    }


    @Test
    void sendsCustomHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/config/channels", exchange -> {
            assertEquals("agent-1", exchange.getRequestHeaders().getFirst("X-Agent-Id"));
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            CliOptions options = new CliOptions();
            set(options, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            set(options, "host", "127.0.0.1");
            set(options, "port", 8088);
            set(options, "profile", "default");
            set(options, "output", com.melon.app.cli.context.CliOutputFormat.PLAIN);
            set(options, "timeoutSeconds", 5);
            CliHttpResponse response = new CliHttpClient().execute(
                    CliContext.from(options),
                    CliCommandSpecs.CHANNELS_LIST,
                    Map.of(),
                    null,
                    Map.of("X-Agent-Id", "agent-1"));
            assertEquals(200, response.statusCode());
        } finally {
            server.stop(0);
        }
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
