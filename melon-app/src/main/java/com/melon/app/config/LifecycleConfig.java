/**
 * @author melon
 */
package com.melon.app.config;

import com.melon.app.cron.CronManager;
import com.melon.app.service.BuiltinSkillInitializer;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.plugin.PluginManager;
import com.melon.core.provider.ProviderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 启动/关闭生命周期. 对应 Python app/_app.py 的 lifespan 函数.
 * Phase 1 同步快速初始化, Phase 2 后台异步加载.
 */
@Configuration
public class LifecycleConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LifecycleConfig.class);
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private ProviderManager providerManager;

    @Autowired
    private MultiAgentManager multiAgentManager;

    @Autowired
    private WorkspaceManager workspaceManager;

    @Autowired
    private BuiltinSkillInitializer builtinSkillInitializer;

    @Autowired(required = false)
    private PluginManager pluginManager;

    @Autowired(required = false)
    private CronManager cronManager;

    private final CountDownLatch initLatch = new CountDownLatch(1);

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Melon starting ===");

        // Phase 1: 同步快速
        configManager.load();
        builtinSkillInitializer.seedAllAgents();
        initAgentWorkspaces();
        providerManager.init(configManager.getConfig());
        multiAgentManager.init();
        log.info("Phase 1 complete: config, providers, state store, builtin skills, workspaces initialized");

        // Agents are created lazily on first chat. Startup should not require model API keys.
        initLatch.countDown();
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

        // 等待进行中的任务完成 (最多 10 秒)
        try {
            if (!initLatch.await(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Timeout waiting {}s for in-progress tasks to complete, proceeding with shutdown",
                        SHUTDOWN_AWAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for in-progress tasks");
        }

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
