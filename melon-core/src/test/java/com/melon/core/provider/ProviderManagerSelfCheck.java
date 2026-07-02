package com.melon.core.provider;

import com.melon.core.config.MelonConfig;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.model.ModelRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ProviderManagerSelfCheck {

    private ProviderManagerSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-provider-check");
        JsonUtils.save(home.resolve("providers.json"), Map.of(
                "local-openai", Map.of(
                        "is_custom", true,
                        "base_url", "http://127.0.0.1:9999/v1",
                        "models", List.of(Map.of("id", "qwen-test", "name", "Qwen Test"))
                ),
                "deepseek", Map.of(
                        "api_key", "test-key",
                        "models", List.of(Map.of("id", "deepseek-v4-flash", "name", "DeepSeek V4 Flash"))
                )
        ));

        MelonConfig config = new MelonConfig();
        config.setHomeDir(home.toString());
        ProviderManager manager = new ProviderManager();
        manager.init(config);

        if (!manager.listProviders().contains("local-openai")) {
            throw new AssertionError("custom provider was not listed");
        }
        if (!manager.listModels("local-openai").contains("qwen-test")) {
            throw new AssertionError("custom provider models were not listed");
        }
        if (!manager.isConfigured("local-openai")) {
            throw new AssertionError("custom provider with base_url should be configured");
        }
        if (!manager.listModels("deepseek").contains("deepseek-v4-flash")) {
            throw new AssertionError("built-in provider configured model was not listed");
        }
        ModelRegistry.resolve("deepseek:deepseek-v4-flash");
    }
}
