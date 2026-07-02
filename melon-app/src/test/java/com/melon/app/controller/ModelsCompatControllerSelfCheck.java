package com.melon.app.controller;

import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.provider.ProviderManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ModelsCompatControllerSelfCheck {

    private ModelsCompatControllerSelfCheck() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-models-controller-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        configManager.load();
        ProviderManager providerManager = new ProviderManager();
        providerManager.init(configManager.getConfig());
        MultiAgentManager agents = new MultiAgentManager(configManager, new WorkspaceManager());
        ModelsCompatController controller = new ModelsCompatController(providerManager, configManager, agents);

        Object body = controller.addModel("deepseek", Map.of("id", "deepseek-v4-flash", "name", "DeepSeek V4 Flash"))
                .block()
                .getBody();
        Map<String, Object> provider = (Map<String, Object>) body;
        String allModels = String.valueOf(provider.get("models")) + String.valueOf(provider.get("extra_models"));
        if (!allModels.contains("deepseek-v4-flash")) {
            throw new AssertionError("added built-in provider model was not returned: " + provider);
        }
        if (occurrences(allModels, "deepseek-v4-flash") != 1) {
            throw new AssertionError("added built-in provider model was duplicated: " + provider);
        }
        Object discover = controller.discoverModels("deepseek", false, null).block().getBody();
        if (!String.valueOf(discover).contains("deepseek-chat")) {
            throw new AssertionError("discover did not return available built-in models: " + discover);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
