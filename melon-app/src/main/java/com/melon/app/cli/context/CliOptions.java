package com.melon.app.cli.context;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class CliOptions {

    @Option(names = "--host", scope = ScopeType.INHERIT, defaultValue = "127.0.0.1", description = "API host")
    private String host;

    @Option(names = "--port", scope = ScopeType.INHERIT, defaultValue = "8088", description = "API port")
    private int port;

    @Option(names = "--base-url", scope = ScopeType.INHERIT, description = "API base URL. Overrides --host and --port")
    private String baseUrl;

    @Option(names = "--profile", scope = ScopeType.INHERIT, defaultValue = "default", description = "Runtime profile")
    private String profile;

    @Option(names = {"-o", "--output"}, scope = ScopeType.INHERIT, defaultValue = "plain",
            converter = CliOutputFormat.Converter.class, description = "Output format: plain, json, table")
    private CliOutputFormat output;

    @Option(names = "--timeout", scope = ScopeType.INHERIT, defaultValue = "10", description = "HTTP timeout in seconds")
    private int timeoutSeconds;

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getProfile() {
        return profile;
    }

    public CliOutputFormat getOutput() {
        return output;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
