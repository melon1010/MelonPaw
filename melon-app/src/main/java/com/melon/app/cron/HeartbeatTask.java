package com.melon.app.cron;

import com.melon.core.config.ConfigManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.HeartbeatConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 心跳任务. 对应 Python heartbeat.py.
 * <p>
 * 定期扫描所有启用 Agent 的 heartbeat 配置，读取工作区 {@code HEARTBEAT.md}
 * 作为 query 注入 main 会话。
 */
@Component
public class HeartbeatTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTask.class);
    private static final String HEARTBEAT_FILE = "HEARTBEAT.md";

    private final CronExecutor cronExecutor;
    private final ConfigManager configManager;

    public HeartbeatTask(CronExecutor cronExecutor, ConfigManager configManager) {
        this.cronExecutor = cronExecutor;
        this.configManager = configManager;
    }

    @Value("${melon.cron.heartbeat_interval_seconds:300}")
    private long intervalSeconds;

    @Value("${melon.cron.heartbeat_enabled:true}")
    private boolean heartbeatEnabled;

    private ScheduledExecutorService scheduler;
    private final Map<String, Long> lastRunByAgent = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        if (!heartbeatEnabled) {
            log.info("Heartbeat task is disabled");
            return;
        }
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "melon-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Heartbeat task started: scanInterval={}s", intervalSeconds);
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
     * 检查所有 Agent 的 HEARTBEAT.md 并触发主动行为.
     */
    private void checkHeartbeat() {
        if (configManager.getConfig().getAgents() == null) {
            return;
        }
        for (var entry : configManager.getConfig().getAgents().entrySet()) {
            checkAgentHeartbeat(entry.getKey(), entry.getValue());
        }
    }

    private void checkAgentHeartbeat(String agentId, AgentConfig agent) {
        if (agent == null || !agent.isEnabled()) {
            return;
        }
        HeartbeatConfig heartbeat = agent.getHeartbeat();
        if (heartbeat == null || !heartbeat.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long everyMs = parseEveryMillis(heartbeat.getEvery());
        long lastRun = lastRunByAgent.getOrDefault(agentId, 0L);
        if (lastRun > 0 && now - lastRun < everyMs) {
            return;
        }
        if (!inActiveHours(agent)) {
            return;
        }

        Path heartbeatFile = configManager.resolveWorkspaceDir(agentId).resolve(HEARTBEAT_FILE);
        if (!Files.exists(heartbeatFile)) {
            return;
        }

        try {
            String instruction = Files.readString(heartbeatFile).trim();
            if (instruction.isBlank()) {
                return;
            }

            lastRunByAgent.put(agentId, now);
            triggerAgent(agentId, instruction);
            log.info("Heartbeat triggered: agent={}, every={}", agentId, heartbeat.getEvery());

        } catch (Exception e) {
            log.error("Failed to run heartbeat for agent={} file={}", agentId, heartbeatFile, e);
        }
    }

    private void triggerAgent(String agentId, String message) {
        String taskId = "heartbeat-" + System.currentTimeMillis();
        cronExecutor.injectMessage(agentId, message, taskId,
                "main", "main", "console", Map.of("source", "heartbeat", "channel", "console"),
                reply -> {}, err -> {});
    }

    private long parseEveryMillis(String every) {
        String value = every == null || every.isBlank() ? "30m" : every.trim().toLowerCase();
        try {
            if (value.matches("\\d+")) return Long.parseLong(value) * 1000L;
            long total = 0L;
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")
                    .matcher(value);
            if (matcher.matches()) {
                if (matcher.group(1) != null) total += Long.parseLong(matcher.group(1)) * 3600L;
                if (matcher.group(2) != null) total += Long.parseLong(matcher.group(2)) * 60L;
                if (matcher.group(3) != null) total += Long.parseLong(matcher.group(3));
            }
            return total > 0 ? total * 1000L : 30L * 60L * 1000L;
        } catch (Exception e) {
            log.warn("Invalid heartbeat every={}, using 30m", every);
            return 30L * 60L * 1000L;
        }
    }

    private boolean inActiveHours(AgentConfig agent) {
        HeartbeatConfig heartbeat = agent.getHeartbeat();
        Map<String, String> activeHours = heartbeat != null ? heartbeat.getActiveHours() : null;
        if (activeHours == null || activeHours.isEmpty()) {
            return true;
        }
        try {
            String start = activeHours.get("start");
            String end = activeHours.get("end");
            if (start == null || end == null || start.isBlank() || end.isBlank()) {
                return true;
            }
            ZoneId zone = ZoneId.of(agent.getTimezone() != null && !agent.getTimezone().isBlank()
                    ? agent.getTimezone()
                    : "UTC");
            LocalTime now = LocalTime.now(zone);
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            if (!startTime.isAfter(endTime)) {
                return !now.isBefore(startTime) && !now.isAfter(endTime);
            }
            return !now.isBefore(startTime) || !now.isAfter(endTime);
        } catch (Exception e) {
            log.warn("Invalid heartbeat activeHours for agent={}, ignoring: {}", agent.getName(), activeHours);
            return true;
        }
    }

    /**
     * 手动触发一次心跳检查 (用于测试).
     */
    public void triggerNow() {
        run();
    }
}
