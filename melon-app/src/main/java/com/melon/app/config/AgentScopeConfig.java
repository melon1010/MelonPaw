/**
 * @author melon
 */
package com.melon.app.config;

import com.melon.core.config.ConfigManager;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.plugin.PluginManager;
import com.melon.core.provider.ProviderManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Spring Bean 配置. 将 core 模块的关键组件注册为 Spring Bean.
 */
@Configuration
public class AgentScopeConfig {

    @Bean
    public WorkspaceManager workspaceManager() {
        return new WorkspaceManager();
    }

    @Bean
    public ConfigManager configManager() {
        ConfigManager cm = new ConfigManager();
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
    public MultiAgentManager multiAgentManager(ConfigManager configManager) {
        MultiAgentManager mgr = new MultiAgentManager(configManager);
        mgr.init();
        return mgr;
    }

    @Bean
    public PluginManager pluginManager(WorkspaceManager workspaceManager) {
        Path pluginsDir = Path.of(System.getProperty("user.home"), ".melon", "workspace", "plugins");
        return new PluginManager(pluginsDir, workspaceManager);
    }
}
