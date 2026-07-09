package com.melon.app.cli.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.spec.CliCommandSpec;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class CliHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CliHttpResponse execute(CliContext context, CliCommandSpec spec) throws Exception {
        return execute(context, spec, Map.of(), null);
    }

    public CliHttpResponse execute(CliContext context, CliCommandSpec spec,
                                   Map<String, String> pathParams, Object body) throws Exception {
        return execute(context, spec, pathParams, body, Map.of());
    }

    public CliHttpResponse execute(CliContext context, CliCommandSpec spec,
                                   Map<String, String> pathParams, Object body,
                                   Map<String, String> headers) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(context.timeout())
                .build();
        String path = spec.expandPath(encodePathParams(pathParams));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(context.baseUrl() + path))
                .timeout(context.timeout())
                .header("Accept", "application/json");
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    builder.header(key, value);
                }
            });
        }

        if ("GET".equals(spec.method())) {
            builder.GET();
        } else if ("DELETE".equals(spec.method())) {
            builder.DELETE();
        } else {
            String payload = body == null ? "{}" : MAPPER.writeValueAsString(body);
            builder.header("Content-Type", "application/json");
            builder.method(spec.method(), HttpRequest.BodyPublishers.ofString(payload));
        }

        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new CliHttpResponse(response.statusCode(), response.body());
        } catch (ConnectException e) {
            throw new CliHttpException("Cannot connect to Melon server at " + context.baseUrl()
                    + ". Start it with: melonpaw app --port " + context.port(), e);
        }
    }

    public int printResponse(CliContext context, CliCommandSpec spec, CliHttpResponse response) {
        if (spec.successStatuses().contains(response.statusCode())) {
            if (response.body() != null && !response.body().isBlank()) {
                System.out.println(new com.melon.app.cli.output.CliOutputRenderer()
                        .render(response.body(), context.outputFormat()));
            }
            return 0;
        }
        System.err.println("Error: HTTP " + response.statusCode());
        if (response.body() != null && !response.body().isBlank()) {
            System.err.println(response.body());
        }
        return 1;
    }

    private Map<String, String> encodePathParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        return params.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
                        .replace("+", "%20")
        ));
    }
}
