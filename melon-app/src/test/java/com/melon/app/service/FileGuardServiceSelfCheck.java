package com.melon.app.service;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class FileGuardServiceSelfCheck {

    private FileGuardServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-file-guard-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        SecuritySettingsService settings = new SecuritySettingsService(configManager);
        FileGuardService guard = new FileGuardService(settings);

        Path secretDir = home.resolve("secret");
        Path secretFile = secretDir.resolve("token.txt");
        Files.createDirectories(secretDir);
        Files.writeString(secretFile, "secret");

        if (guard.isSensitive(secretFile)) {
            throw new AssertionError("disabled file guard should not block");
        }

        settings.saveFileGuard(Map.of("enabled", true, "paths", List.of(secretDir.toString() + "/")));
        try {
            guard.assertAllowed(secretFile);
            throw new AssertionError("sensitive child file was not blocked");
        } catch (SecurityException expected) {
            if (!"SENSITIVE_FILE_BLOCKED".equals(expected.getMessage())) {
                throw expected;
            }
        }

        Path publicFile = home.resolve("public.txt");
        Files.writeString(publicFile, "ok");
        guard.assertAllowed(publicFile);
    }
}
