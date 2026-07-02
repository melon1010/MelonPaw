package com.melon.app.controller;

import com.melon.app.service.EnvService;
import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class EnvControllerSelfCheck {

    private EnvControllerSelfCheck() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-env-controller-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(home.resolve("config.yaml"));
        configManager.setHomeDir(home.toString());
        configManager.load();

        EnvController controller = new EnvController(new EnvService(configManager));
        Object saved = controller.saveEnvs(Map.of("DEEPSEEK_API_KEY", "test-key")).block().getBody();
        assertEnvList(saved, "DEEPSEEK_API_KEY", "test-key");
        if (!"test-key".equals(System.getProperty("DEEPSEEK_API_KEY"))) {
            throw new AssertionError("env var was not injected into system properties");
        }

        Object listed = controller.listEnvs().block().getBody();
        assertEnvList(listed, "DEEPSEEK_API_KEY", "test-key");

        Object deleted = controller.deleteEnv("DEEPSEEK_API_KEY").block().getBody();
        if (!(deleted instanceof List<?> list) || !list.isEmpty()) {
            throw new AssertionError("delete did not return an empty env list: " + deleted);
        }
        if (System.getProperty("DEEPSEEK_API_KEY") != null) {
            throw new AssertionError("env var system property was not cleared");
        }
    }

    private static void assertEnvList(Object body, String key, String value) {
        if (!(body instanceof List<?> list)) {
            throw new AssertionError("env response is not a list: " + body);
        }
        boolean found = list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(item -> key.equals(item.get("key")) && value.equals(item.get("value")));
        if (!found) {
            throw new AssertionError("env list missing " + key + ": " + body);
        }
    }
}
