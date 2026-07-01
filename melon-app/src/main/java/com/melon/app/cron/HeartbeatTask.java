package com.melon.app.cron;

import com.melon.core.config.ConfigManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 心跳任务. 对应 Python heartbeat.py.
 * <p>
 * 定期检查 {@code HEARTBEAT.md} 文件, 若存在且包含活跃触发指令,
 * 则通过 {@link CronExecutor} 向 Agent 注入消息, 触发主动行为.
 * <p>
 * 心跳间隔通过 {@code melon.cron.heartbeat_interval_seconds} 配置, 默认 300 秒 (5 分钟).
 */
@Component
public class HeartbeatTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTask.class);
    private static final String HEARTBEAT_FILE = "HEARTBEAT.md";
    private static final String TRIGGER_MARKER = "<!-- trigger:active -->";

    @Autowired
    private CronExecutor cronExecutor;

    @Autowired
    private ConfigManager configManager;

    @Value("${melon.cron.heartbeat_interval_seconds:300}")
    private long intervalSeconds;

    @Value("${melon.cron.heartbeat_enabled:true}")
    private boolean heartbeatEnabled;

    private ScheduledExecutorService scheduler;
    private Path workspaceDir;
    private long lastModified = 0;

    @PostConstruct
    public void start() {
        if (!heartbeatEnabled) {
            log.info("Heartbeat task is disabled");
            return;
        }
        resolveWorkspaceDir();
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "melon-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Heartbeat task started: interval={}s, workspace={}", intervalSeconds, workspaceDir);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("Heartbeat task stopped");
        }
    }

    @Override
    public void run() {
        try {
            checkHeartbeat();
        } catch (Exception e) {
            log.error("Heartbeat check failed", e);
        }
    }

    /**
     * 检查 HEARTBEAT.md 并触发 Agent 主动行为.
     */
    private void checkHeartbeat() {
        Path heartbeatFile = workspaceDir.resolve(HEARTBEAT_FILE);
        if (!Files.exists(heartbeatFile)) {
            return; // 无心跳文件, 静默跳过
        }

        try {
            long currentModified = Files.getLastModifiedTime(heartbeatFile).toMillis();
            String content = Files.readString(heartbeatFile);

            // 检查是否有活跃触发标记
            boolean hasActiveTrigger = content.contains(TRIGGER_MARKER);

            // 检查文件是否有更新 (避免重复触发)
            if (!hasActiveTrigger) {
                return;
            }

            if (currentModified == lastModified) {
                // 文件未更新, 但触发标记仍在, 使用默认消息
                triggerAgent("Heartbeat check: please check pending tasks and continue if needed.");
                return;
            }

            lastModified = currentModified;

            // 提取心跳指令内容 (移除标记后的纯文本)
            String instruction = content.replace(TRIGGER_MARKER, "").trim();
            if (instruction.isBlank()) {
                instruction = "Heartbeat check: please check pending tasks and continue if needed.";
            }

            triggerAgent(instruction);
            log.info("Heartbeat triggered agent proactive behavior");

        } catch (Exception e) {
            log.error("Failed to read heartbeat file {}", heartbeatFile, e);
        }
    }

    /**
     * 通过 CronExecutor 向默认 Agent 注入消息.
     */
    private void triggerAgent(String message) {
        cronExecutor.injectMessage("default", message, "heartbeat-" + System.currentTimeMillis());
    }

    /**
     * 从配置解析工作目录.
     */
    private void resolveWorkspaceDir() {
        workspaceDir = configManager.resolveWorkspaceDir("default");
    }

    /**
     * 手动触发一次心跳检查 (用于测试).
     */
    public void triggerNow() {
        run();
    }
}
