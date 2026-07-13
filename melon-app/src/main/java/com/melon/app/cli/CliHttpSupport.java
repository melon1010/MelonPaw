package com.melon.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.context.CliOptionResolver;
import com.melon.app.cli.http.CliHttpClient;
import com.melon.app.cli.http.CliHttpResponse;
import com.melon.app.cli.output.CliOutputRenderer;
import com.melon.app.cli.spec.CliCommandSpec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CliHttpSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CliHttpSupport() {
    }

    static Map<String, String> agentHeader(String agent) {
        return agent == null || agent.isBlank() ? Map.of() : Map.of("X-Agent-Id", agent);
    }

    static Map<String, Object> setBody(List<String> fields) {
        return new LinkedHashMap<>(com.melon.app.cli.spec.CliKeyValueParser.parsePairs(fields));
    }

    static int request(CommandSpec commandSpec, String method, String path, Object body) {
        return request(commandSpec, method, path, body, Map.of());
    }

    static int request(CommandSpec commandSpec, String method, String path, Object body, Map<String, String> headers) {
        CliCommandSpec spec = spec(method, path);
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        try {
            CliHttpResponse response = new CliHttpClient().execute(context, spec, Map.of(), body, headers);
            return new CliHttpClient().printResponse(context, spec, response);
        } catch (ConnectException e) {
            System.err.println("Cannot connect to Melon server at " + context.baseUrl()
                    + ". Start it with: melonpaw app --port " + context.port());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static int multipart(CommandSpec commandSpec, String path, Path file, String fileField,
                         Map<String, Object> fields, Map<String, String> headers) {
        if (file == null || !Files.isRegularFile(file)) {
            System.err.println("--file must point to a readable file.");
            return 1;
        }
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        try {
            String boundary = "melonpaw-" + UUID.randomUUID();
            byte[] body = multipartBody(boundary, file, fileField, fields);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(context.baseUrl() + path))
                    .timeout(context.timeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach((key, value) -> {
                if (value != null && !value.isBlank()) builder.header(key, value);
            });
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(context.timeout()).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new CliHttpClient().printResponse(context, spec("POST", path),
                    new CliHttpResponse(response.statusCode(), response.body()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static int download(CommandSpec commandSpec, String path, Path output, Map<String, String> headers) {
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(context.baseUrl() + path))
                    .timeout(context.timeout())
                    .GET();
            headers.forEach((key, value) -> {
                if (value != null && !value.isBlank()) builder.header(key, value);
            });
            HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(context.timeout()).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Error: HTTP " + response.statusCode());
                System.err.println(new String(response.body(), StandardCharsets.UTF_8));
                return 1;
            }
            Path target = output != null ? output : Path.of(filename(response, "melonpaw-download.bin"));
            Files.write(target, response.body());
            System.out.println(target);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static int sse(CommandSpec commandSpec, String path, Object body, Map<String, String> headers) {
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(context.baseUrl() + path))
                    .timeout(context.timeout())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body == null ? Map.of() : body)));
            headers.forEach((key, value) -> {
                if (value != null && !value.isBlank()) builder.header(key, value);
            });
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(context.timeout()).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Error: HTTP " + response.statusCode());
                System.err.println(response.body());
                return 1;
            }
            String lastJson = "";
            for (String line : response.body().split("\\R")) {
                if (!line.startsWith("data:")) continue;
                lastJson = line.substring(5).trim();
                System.out.println(new CliOutputRenderer().render(lastJson, context.outputFormat()));
            }
            if (lastJson.isBlank()) {
                System.out.println(response.body());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static String query(Map<String, ?> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((key, value) -> {
            if (value == null || String.valueOf(value).isBlank()) return;
            out.append(out.isEmpty() ? "?" : "&")
                    .append(url(key))
                    .append("=")
                    .append(url(String.valueOf(value)));
        });
        return out.toString();
    }

    static String path(String value) {
        return value == null ? "" : java.util.Arrays.stream(value.split("/", -1))
                .map(CliHttpSupport::url)
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static CliCommandSpec spec(String method, String path) {
        CliCommandSpec.Builder builder = CliCommandSpec.builder(method + " " + path);
        return switch (method) {
            case "POST" -> builder.post(path).build();
            case "PUT" -> builder.put(path).build();
            case "PATCH" -> builder.patch(path).build();
            case "DELETE" -> builder.delete(path).build();
            default -> builder.get(path).build();
        };
    }

    private static byte[] multipartBody(String boundary, Path file, String fileField, Map<String, Object> fields) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (fields != null) {
            for (var entry : fields.entrySet()) {
                if (entry.getValue() == null) continue;
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + file.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String filename(HttpResponse<?> response, String fallback) {
        return response.headers().firstValue("Content-Disposition")
                .map(value -> value.replaceFirst("(?i).*filename=\"?([^\";]+)\"?.*", "$1"))
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }
}
