package com.melon.core.env;

import java.util.Map;

public final class EnvBridge {

    private static final String KEYS_PROPERTY = "melon.env.keys";

    private EnvBridge() {
    }

    public static void injectAll(Map<String, String> envs) {
        if (envs == null || envs.isEmpty()) {
            System.clearProperty(KEYS_PROPERTY);
            return;
        }
        System.setProperty(KEYS_PROPERTY, String.join("\n", envs.keySet()));
        envs.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                System.setProperty(key, value);
            }
        });
    }

    public static void applyToProcessEnv(Map<String, String> target) {
        if (target == null) {
            return;
        }
        String keys = System.getProperty(KEYS_PROPERTY, "");
        if (keys.isBlank()) {
            return;
        }
        for (String key : keys.split("\\R")) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = System.getProperty(key);
            if (value != null) {
                target.put(key, value);
            }
        }
    }
}
