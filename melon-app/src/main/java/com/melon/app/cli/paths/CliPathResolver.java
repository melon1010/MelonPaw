package com.melon.app.cli.paths;

import java.nio.file.Path;

public class CliPathResolver {

    public static final String MELON_HOME_ENV = "MELON_HOME";
    public static final String MELONPAW_HOME_ENV = "MELONPAW_HOME";
    public static final String MELON_HOME_PROPERTY = "melon.home";

    private final Path homeDir;

    public CliPathResolver() {
        this(resolveHomeDir());
    }

    public CliPathResolver(Path homeDir) {
        this.homeDir = homeDir.toAbsolutePath().normalize();
    }

    public Path homeDir() {
        return homeDir;
    }

    public Path workspaceDir(String workspace) {
        if (workspace == null || workspace.isBlank() || "default".equals(workspace)) {
            return homeDir.resolve("workspaces").resolve("default");
        }
        return expandUser(workspace);
    }

    public Path configPath() {
        return homeDir.resolve("config.yaml");
    }

    public Path cacheDir() {
        return homeDir.resolve("cache");
    }

    public Path logDir() {
        return homeDir.resolve("logs");
    }

    public Path stateFile(String name) {
        return homeDir.resolve(name);
    }

    public Path resolveUnderHome(String first, String... more) {
        return homeDir.resolve(Path.of(first, more)).normalize();
    }

    public static Path expandUser(String value) {
        if (value == null || value.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path resolveHomeDir() {
        String configured = System.getProperty(MELON_HOME_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MELON_HOME_ENV);
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MELONPAW_HOME_ENV);
        }
        if (configured == null || configured.isBlank()) {
            configured = "~/.melonAI";
        }
        return expandUser(configured);
    }
}
