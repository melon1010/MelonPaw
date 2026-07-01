package com.melon.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 工具. 简化 HTTP 请求, 对应 Python http_utils.
 */
public final class HttpUtils {

    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpUtils() {}

    /**
     * 发送 GET 请求, 返回响应体字符串.
     */
    public static String get(String url) {
        return get(url, null);
    }

    /**
     * 发送 GET 请求 (带自定义 header), 返回响应体字符串.
     */
    public static String get(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (IOException | InterruptedException e) {
            log.error("GET request failed: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * 发送 POST 请求 (JSON body), 返回响应体字符串.
     */
    public static String postJson(String url, String jsonBody) {
        return postJson(url, jsonBody, null);
    }

    /**
     * 发送 POST 请求 (JSON body, 带自定义 header), 返回响应体字符串.
     */
    public static String postJson(String url, String jsonBody, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (IOException | InterruptedException e) {
            log.error("POST request failed: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * 发送 PUT 请求 (JSON body), 返回响应状态码.
     */
    public static int putJson(String url, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (IOException | InterruptedException e) {
            log.error("PUT request failed: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /**
     * 发送 DELETE 请求, 返回响应状态码.
     */
    public static int delete(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();
            HttpResponse<String> resp = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (IOException | InterruptedException e) {
            log.error("DELETE request failed: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }
}
