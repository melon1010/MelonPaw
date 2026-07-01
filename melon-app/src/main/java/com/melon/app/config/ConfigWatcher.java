package com.melon.app.config;

import com.melon.core.config.ConfigManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.*;

/**
 * 配置文件监视器. 对应 Python config/watcher.py.
 * <p>
 * 使用 {@link WatchService} 监视 {@code config.yaml} 文件变化,
 * 变更时自动触发 {@link ConfigManager#reload()}.
 * <p>
 * 包含简单的防抖逻辑, 避免文件保存时的多次事件触发重复重载.
 */
@Component
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);
    private static final long DEBOUNCE_MS = 1000; // 防抖间隔

    @Autowired
    private ConfigManager configManager;

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running = false;
    private volatile long lastReloadTime = 0;

    @PostConstruct
    public void start() {
        Path configPath = configManager.getConfigPath();
        Path configDir = configPath.getParent();
        String configFileName = configPath.getFileName().toString();

        if (!Files.exists(configDir)) {
            log.warn("Config directory does not exist: {}, watcher disabled", configDir);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            configDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);

            running = true;
            watchThread = new Thread(() -> watchLoop(configDir, configFileName), "melon-config-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            log.info("ConfigWatcher started: watching {} for {}", configDir, configFileName);
        } catch (Exception e) {
            log.error("Failed to start ConfigWatcher", e);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception e) {
                log.warn("Failed to close WatchService", e);
            }
        }
        log.info("ConfigWatcher stopped");
    }

    /**
     * 监视循环.
     */
    private void watchLoop(Path configDir, String configFileName) {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("WatchService error", e);
                break;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.context() == null) {
                    continue;
                }
                Path changedFile = configDir.resolve(event.context().toString());
                String fileName = changedFile.getFileName().toString();

                if (configFileName.equals(fileName)) {
                    handleConfigChange();
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("WatchKey no longer valid, stopping watcher");
                break;
            }
        }
    }

    /**
     * 处理配置变更 (带防抖).
     */
    private void handleConfigChange() {
        long now = System.currentTimeMillis();
        if (now - lastReloadTime < DEBOUNCE_MS) {
            log.debug("Config change debounced (within {}ms)", DEBOUNCE_MS);
            return;
        }
        lastReloadTime = now;

        log.info("Config file change detected, reloading...");
        try {
            configManager.reload();
            log.info("Config reloaded successfully");
        } catch (Exception e) {
            log.error("Failed to reload config", e);
        }
    }

    /**
     * 手动触发重载 (用于测试).
     */
    public void triggerReload() {
        handleConfigChange();
    }
}
