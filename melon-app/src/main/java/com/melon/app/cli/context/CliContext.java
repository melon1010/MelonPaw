package com.melon.app.cli.context;

import java.time.Duration;

public final class CliContext {

    private final String host;
    private final int port;
    private final String baseUrl;
    private final String profile;
    private final CliOutputFormat outputFormat;
    private final Duration timeout;

    private CliContext(String host, int port, String baseUrl, String profile,
                       CliOutputFormat outputFormat, Duration timeout) {
        this.host = host;
        this.port = port;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.profile = profile;
        this.outputFormat = outputFormat;
        this.timeout = timeout;
    }

    public static CliContext from(CliOptions options) {
        String host = blankToDefault(options.getHost(), "127.0.0.1");
        int port = options.getPort() > 0 ? options.getPort() : 8088;
        String baseUrl = options.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://" + host + ":" + port;
        }
        String profile = blankToDefault(options.getProfile(), "default");
        CliOutputFormat output = options.getOutput() == null ? CliOutputFormat.PLAIN : options.getOutput();
        int timeoutSeconds = Math.max(1, options.getTimeoutSeconds());
        return new CliContext(host, port, baseUrl, profile, output, Duration.ofSeconds(timeoutSeconds));
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String profile() {
        return profile;
    }

    public CliOutputFormat outputFormat() {
        return outputFormat;
    }

    public Duration timeout() {
        return timeout;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
