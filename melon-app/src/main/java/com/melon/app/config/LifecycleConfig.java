package com.melon.app.config;

import com.melon.app.cron.CronManager;
import com.melon.app.service.BuiltinSkillInitializer;
import com.melon.app.service.TokenUsageService;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.plugin.PluginManager;
import com.melon.core.provider.ProviderManager;
import com.melon.tools.agent.ListAgentsTool;
import com.melon.tools.util.GetTokenUsageTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * 启动/关闭生命周期. 对应 Python app/_app.py 的 lifespan 函数.
 * Phase 1 同步快速初始化, Phase 2 后台异步加载.
 */
@Configuration
public class LifecycleConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LifecycleConfig.class);

    private final ConfigManager configManager;
    private final ProviderManager providerManager;
    private final MultiAgentManager multiAgentManager;
    private final WorkspaceManager workspaceManager;
    private final BuiltinSkillInitializer builtinSkillInitializer;
    private final TokenUsageService tokenUsageService;
    private final PluginManager pluginManager;
    private final CronManager cronManager;

    public LifecycleConfig(ConfigManager configManager,
                           ProviderManager providerManager,
                           MultiAgentManager multiAgentManager,
                           WorkspaceManager workspaceManager,
                           BuiltinSkillInitializer builtinSkillInitializer,
                           TokenUsageService tokenUsageService,
                           PluginManager pluginManager,
                           CronManager cronManager) {
        this.configManager = configManager;
        this.providerManager = providerManager;
        this.multiAgentManager = multiAgentManager;
        this.workspaceManager = workspaceManager;
        this.builtinSkillInitializer = builtinSkillInitializer;
        this.tokenUsageService = tokenUsageService;
        this.pluginManager = pluginManager;
        this.cronManager = cronManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Melon starting ===");

        // Phase 1: 同步快速
        configManager.load();
        builtinSkillInitializer.seedAllAgents();
        initAgentWorkspaces();
        providerManager.init(configManager.getConfig());
        multiAgentManager.init();
        ListAgentsTool.setAgentListSupplier(multiAgentManager::listAgents);
        GetTokenUsageTool.setTokenUsageProvider((days, modelName, providerId) -> {
            String start = java.time.LocalDate.now().minusDays(days != null ? days : 30L).toString();
            String end = java.time.LocalDate.now().toString();
            return tokenUsageService.getSummary(start, end, providerId, modelName);
        });
        log.info("Phase 1 complete: config, providers, state store, builtin skills, workspaces initialized");

        log.info("Phase 2 skipped: agents will start lazily on demand");

        log.info("=== Melon ready on {}:{} ===",
                configManager.getConfig().getServer().getHost(),
                configManager.getConfig().getServer().getPort());
    }

    private void initAgentWorkspaces() {
        configManager.getConfig().getAgents().forEach((agentId, agentConfig) -> {
            var workspaceDir = configManager.resolveWorkspaceDir(agentId);
            workspaceManager.initWorkspace(workspaceDir);
            workspaceManager.writeAgentJson(workspaceDir, agentId, agentConfig);
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("=== Melon shutting down ===");

        // 停止所有 Agent
        try {
            multiAgentManager.stopAll();
        } catch (Exception e) {
            log.error("Error stopping agents", e);
        }

        // 卸载所有插件
        if (pluginManager != null) {
            try {
                pluginManager.unloadAll();
            } catch (Exception e) {
                log.error("Error unloading plugins", e);
            }
        }

        // 关闭定时任务调度器
        if (cronManager != null) {
            try {
                cronManager.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down cron manager", e);
            }
        }

        log.info("=== Melon shutdown complete ===");
    }
}
