package com.melon.app.config;

import com.melon.core.config.ConfigManager;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.ToolkitContributor;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.plugin.PluginManager;
import com.melon.core.provider.ProviderManager;
import com.melon.app.service.TokenUsageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * Spring Bean 配置. 将 core 模块的关键组件注册为 Spring Bean.
 */
@Configuration
public class AgentScopeConfig {

    @Value("${melon.home_dir:~/.melon}")
    private String melonHomeDir;

    @Bean
    public WorkspaceManager workspaceManager() {
        return new WorkspaceManager();
    }

    @Bean
    public ConfigManager configManager() {
        ConfigManager cm = new ConfigManager();
        cm.setConfigPath(expandHome(melonHomeDir).resolve("config.yaml"));
        cm.setHomeDir(melonHomeDir);
        cm.load();
        return cm;
    }

    @Bean
    public ProviderManager providerManager(ConfigManager configManager) {
        ProviderManager pm = new ProviderManager();
        pm.init(configManager.getConfig());
        return pm;
    }

    @Bean
    public MultiAgentManager multiAgentManager(ConfigManager configManager,
                                               WorkspaceManager workspaceManager,
                                               ProviderManager providerManager,
                                               TokenUsageService tokenUsageService,
                                               ObjectProvider<ToolkitContributor> toolkitContributors) {
        List<ToolkitContributor> contributors = toolkitContributors.orderedStream().toList();
        MultiAgentManager mgr = new MultiAgentManager(configManager, workspaceManager,
                (agentId, sessionId, modelName, usage, latencyMs) ->
                        tokenUsageService.recordUsage(sessionId, agentId, modelName,
                                usage.promptTokens(), usage.completionTokens(), usage.totalTokens()),
                contributors, providerManager);
        mgr.init();
        return mgr;
    }

    @Bean
    public PluginManager pluginManager(WorkspaceManager workspaceManager) {
        Path pluginsDir = expandHome(melonHomeDir).resolve("workspace").resolve("plugins");
        return new PluginManager(pluginsDir, workspaceManager);
    }

    private Path expandHome(String path) {
        String value = path == null || path.isBlank() ? "~/.melon" : path;
        if (value.startsWith("~")) {
            value = System.getProperty("user.home") + value.substring(1);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
