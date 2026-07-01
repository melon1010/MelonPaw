package com.melon.app.cron;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务管理器. 对应 Python cron/manager.py.
 * <p>
 * 基于 {@link ScheduledExecutorService}, 支持三种触发器:
 * <ul>
 *   <li>CRON - cron 表达式 (Spring {@link CronExpression})</li>
 *   <li>INTERVAL - 固定间隔 (毫秒)</li>
 *   <li>ONE_SHOT - 单次延迟执行 (毫秒)</li>
 * </ul>
 */
@Component
public class CronManager {

    private static final Logger log = LoggerFactory.getLogger(CronManager.class);
    private static final int POOL_SIZE = 4;

    private final ScheduledExecutorService executor;
    private final CronExecutor cronExecutor;

    /** 所有已注册的定时任务. */
    private final ConcurrentHashMap<String, CronJob> jobs = new ConcurrentHashMap<>();
    /** 调度句柄, 用于取消. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public CronManager(CronExecutor cronExecutor) {
        this.cronExecutor = cronExecutor;
        this.executor = new ScheduledThreadPoolExecutor(POOL_SIZE, r -> {
            Thread t = new Thread(r, "melon-cron-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });
    }

    // ======================== Public API ========================

    /**
     * 注册并调度一个定时任务.
     *
     * @param job 任务定义 (id 可为空, 自动生成)
     * @return 注册后的任务 (含生成的 id)
     */
    public CronJob schedule(CronJob job) {
        if (job.getId() == null || job.getId().isBlank()) {
            job.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        if (job.getName() == null || job.getName().isBlank()) {
            job.setName("cron-" + job.getId());
        }
        if (!job.isEnabled()) {
            log.info("Cron job {} registered but disabled, skip scheduling", job.getId());
            jobs.put(job.getId(), job);
            return job;
        }

        doSchedule(job);
        jobs.put(job.getId(), job);
        log.info("Cron job scheduled: id={}, name={}, type={}", job.getId(), job.getName(), job.getTriggerType());
        return job;
    }

    /**
     * 取消并移除定时任务.
     */
    public boolean cancel(String id) {
        CronJob job = jobs.remove(id);
        if (job == null) {
            return false;
        }
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
        log.info("Cron job cancelled: id={}", id);
        return true;
    }

    /**
     * 列出所有定时任务.
     */
    public List<CronJob> list() {
        return new ArrayList<>(jobs.values());
    }

    /**
     * 获取指定定时任务.
     */
    public CronJob get(String id) {
        return jobs.get(id);
    }

    /**
     * 启用/禁用定时任务.
     */
    public CronJob toggle(String id, boolean enabled) {
        CronJob job = jobs.get(id);
        if (job == null) {
            return null;
        }
        job.setEnabled(enabled);
        // 重新调度
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
        if (enabled) {
            doSchedule(job);
        }
        log.info("Cron job {} {}", id, enabled ? "enabled" : "disabled");
        return job;
    }

    /**
     * 启动所有已注册但未调度的任务.
     */
    public void startAll() {
        for (CronJob job : jobs.values()) {
            if (job.isEnabled() && !scheduledTasks.containsKey(job.getId())) {
                doSchedule(job);
            }
        }
        log.info("All cron jobs started: {} total", jobs.size());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CronManager, {} jobs", jobs.size());
        for (ScheduledFuture<?> future : scheduledTasks.values()) {
            future.cancel(false);
        }
        scheduledTasks.clear();
        jobs.clear();
        executor.shutdownNow();
        log.info("CronManager shut down");
    }

    // ======================== Scheduling Logic ========================

    private void doSchedule(CronJob job) {
        switch (job.getTriggerType()) {
            case CRON -> scheduleCron(job);
            case INTERVAL -> scheduleInterval(job);
            case ONE_SHOT -> scheduleOneShot(job);
        }
    }

    /**
     * Cron 表达式调度: 计算下一次执行时间, 延迟调度, 执行后重新调度.
     */
    private void scheduleCron(CronJob job) {
        try {
            CronExpression cron = CronExpression.parse(job.getCronExpression());
            scheduleNextCron(job, cron);
        } catch (IllegalArgumentException e) {
            log.error("Invalid cron expression '{}' for job {}", job.getCronExpression(), job.getId(), e);
        }
    }

    private void scheduleNextCron(CronJob job, CronExpression cron) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cron.next(now);
        if (next == null) {
            log.warn("No future execution for cron job {}, expression exhausted", job.getId());
            return;
        }
        long delayMs = Duration.between(now, next).toMillis();
        job.setNextRun(next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());

        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                executeJob(job);
            } catch (Exception e) {
                log.error("Cron job {} execution failed", job.getId(), e);
            } finally {
                if (job.isEnabled()) {
                    scheduleNextCron(job, cron);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        scheduledTasks.put(job.getId(), future);
    }

    /**
     * 固定间隔调度.
     */
    private void scheduleInterval(CronJob job) {
        long interval = job.getIntervalMs();
        if (interval <= 0) {
            log.error("Invalid interval {} for job {}", interval, job.getId());
            return;
        }
        job.setNextRun(System.currentTimeMillis() + interval);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                executeJob(job);
            } catch (Exception e) {
                log.error("Interval job {} execution failed", job.getId(), e);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        scheduledTasks.put(job.getId(), future);
    }

    /**
     * 单次延迟调度.
     */
    private void scheduleOneShot(CronJob job) {
        long delay = job.getDelayMs();
        if (delay < 0) {
            log.error("Invalid delay {} for job {}", delay, job.getId());
            return;
        }
        job.setNextRun(System.currentTimeMillis() + delay);
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                executeJob(job);
            } catch (Exception e) {
                log.error("One-shot job {} execution failed", job.getId(), e);
            } finally {
                // 执行完成后移除
                scheduledTasks.remove(job.getId());
                jobs.remove(job.getId());
                log.info("One-shot job {} completed and removed", job.getId());
            }
        }, delay, TimeUnit.MILLISECONDS);
        scheduledTasks.put(job.getId(), future);
    }

    /**
     * 执行任务: 委托给 CronExecutor.
     */
    private void executeJob(CronJob job) {
        job.setLastRun(System.currentTimeMillis());
        cronExecutor.execute(job);
    }

    // ======================== Data Model ========================

    /**
     * 触发器类型.
     */
    public enum TriggerType {
        CRON,
        INTERVAL,
        ONE_SHOT
    }

    /**
     * 定时任务定义.
     */
    public static class CronJob {
        private String id;
        private String name;
        private TriggerType triggerType = TriggerType.INTERVAL;
        private String cronExpression;   // CRON 类型使用
        private long intervalMs;          // INTERVAL 类型使用
        private long delayMs;             // ONE_SHOT 类型使用
        private String agentId = "default";
        private String message;
        private boolean enabled = true;
        private long lastRun;
        private long nextRun;

        // --- factory ---

        public static CronJob fromMap(Map<String, Object> body) {
            CronJob job = new CronJob();
            if (body.get("id") != null) job.setId((String) body.get("id"));
            if (body.get("name") != null) job.setName((String) body.get("name"));
            if (body.get("cron_expression") != null) job.setCronExpression((String) body.get("cron_expression"));
            if (body.get("agent_id") != null) job.setAgentId((String) body.get("agent_id"));
            if (body.get("message") != null) job.setMessage((String) body.get("message"));
            if (body.get("trigger_type") != null) {
                job.setTriggerType(TriggerType.valueOf(((String) body.get("trigger_type")).toUpperCase()));
            }
            if (body.get("interval_ms") != null) {
                job.setIntervalMs(((Number) body.get("interval_ms")).longValue());
            }
            if (body.get("delay_ms") != null) {
                job.setDelayMs(((Number) body.get("delay_ms")).longValue());
            }
            if (body.get("enabled") != null) {
                job.setEnabled((Boolean) body.get("enabled"));
            }
            return job;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("trigger_type", triggerType);
            m.put("cron_expression", cronExpression);
            m.put("interval_ms", intervalMs);
            m.put("delay_ms", delayMs);
            m.put("agent_id", agentId);
            m.put("message", message);
            m.put("enabled", enabled);
            m.put("last_run", lastRun);
            m.put("next_run", nextRun);
            return m;
        }

        // --- getters/setters ---

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public TriggerType getTriggerType() { return triggerType; }
        public void setTriggerType(TriggerType triggerType) { this.triggerType = triggerType; }

        public String getCronExpression() { return cronExpression; }
        public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getLastRun() { return lastRun; }
        public void setLastRun(long lastRun) { this.lastRun = lastRun; }

        public long getNextRun() { return nextRun; }
        public void setNextRun(long nextRun) { this.nextRun = nextRun; }
    }
}
