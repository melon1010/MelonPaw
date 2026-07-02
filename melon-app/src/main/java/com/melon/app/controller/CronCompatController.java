package com.melon.app.controller;

import com.melon.app.cron.CronExecutor;
import com.melon.app.cron.CronManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.cron.CronJobStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

/**
 * QwenPaw frontend-compatible cron API aliases.
 */
@RestController
@RequestMapping("/api/cron")
public class CronCompatController {

    private final ConfigManager configManager;
    private final CronManager cronManager;
    private final CronExecutor cronExecutor;

    public CronCompatController(ConfigManager configManager, CronManager cronManager, CronExecutor cronExecutor) {
        this.configManager = configManager;
        this.cronManager = cronManager;
        this.cronExecutor = cronExecutor;
    }

    @GetMapping("/jobs")
    public Mono<ResponseEntity<?>> listJobs(@RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            syncScheduledJobs(agentId, jobs);
            return ResponseEntity.ok(jobs);
        });
    }

    @PostMapping("/jobs")
    public Mono<ResponseEntity<?>> createJob(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            Map<String, Object> job = CronJobStore.normalize(body, UUID.randomUUID().toString());
            jobs.add(job);
            saveJobs(agentId, jobs);
            syncScheduledJob(agentId, job, true);
            return ResponseEntity.ok(job);
        });
    }

    @GetMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> getJob(@PathVariable String id,
                                          @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> view = new LinkedHashMap<>(job);
            view.put("state", stateFor(job));
            view.put("history", historyFor(job));
            return ResponseEntity.ok(view);
        });
    }

    @PutMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> replaceJob(@PathVariable String id, @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            for (int i = 0; i < jobs.size(); i++) {
                if (CronJobStore.matches(jobs.get(i), id)) {
                    Map<String, Object> updated = CronJobStore.normalize(body, id);
                    updated.put("created_at", jobs.get(i).get("created_at"));
                    jobs.set(i, updated);
                    saveJobs(agentId, jobs);
                    syncScheduledJob(agentId, updated, true);
                    return ResponseEntity.ok(updated);
                }
            }
            return ResponseEntity.notFound().build();
        });
    }

    @DeleteMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> deleteJob(@PathVariable String id,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            boolean removed = jobs.removeIf(job -> CronJobStore.matches(job, id));
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            saveJobs(agentId, jobs);
            cronManager.cancel(runtimeId(agentId, id));
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/jobs/{id}/pause")
    public Mono<ResponseEntity<?>> pauseJob(@PathVariable String id,
                                            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return setEnabled(agentId, id, false);
    }

    @PostMapping("/jobs/{id}/resume")
    public Mono<ResponseEntity<?>> resumeJob(@PathVariable String id,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return setEnabled(agentId, id, true);
    }

    @PostMapping("/jobs/{id}/run")
    public Mono<ResponseEntity<?>> runJob(@PathVariable String id,
                                          @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            if (booleanValue(job.getOrDefault("enabled", true), true) == false) {
                return ResponseEntity.status(409).body(Map.of("detail", "Cron job is disabled"));
            }
            String prompt = promptValue(job);
            if (prompt.isBlank()) {
                return ResponseEntity.status(409).body(Map.of("detail", "Cron job prompt is empty"));
            }
            String runAt = Instant.now().toString();
            String jobId = String.valueOf(job.get("id"));
            String taskId = "manual-" + jobId + "-" + System.currentTimeMillis();
            markRun(agentId, id, runAt, "manual", "running", null);
            cronExecutor.injectMessage(AgentRequestSupport.agentId(agentId), prompt, taskId,
                    reply -> markRun(agentId, id, runAt, "manual", "success", null),
                    err -> markRun(agentId, id, runAt, "manual", "error", err.getMessage()));
            return ResponseEntity.ok(Map.of(
                    "started", true,
                    "job_id", jobId,
                    "run_at", runAt
            ));
        });
    }

    @GetMapping("/jobs/{id}/state")
    public Mono<ResponseEntity<?>> jobState(@PathVariable String id,
                                            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(stateFor(job));
        });
    }

    @GetMapping("/jobs/{id}/history")
    public Mono<ResponseEntity<?>> history(@PathVariable String id,
                                           @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(historyFor(job));
        });
    }

    @GetMapping("/dispatch-targets")
    public Mono<ResponseEntity<?>> dispatchTargets() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "channels", List.of(),
                "items", List.of(),
                "enabled", false
        )));
    }

    private Mono<ResponseEntity<?>> setEnabled(String agentId, String id, boolean enabled) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            for (Map<String, Object> job : jobs) {
                if (CronJobStore.matches(job, id)) {
                    job.put("enabled", enabled);
                    job.put("paused", !enabled);
                    saveJobs(agentId, jobs);
                    if (enabled) {
                        syncScheduledJob(agentId, job, true);
                    } else {
                        cronManager.cancel(runtimeId(agentId, String.valueOf(job.get("id"))));
                    }
                    return ResponseEntity.noContent().build();
                }
            }
            return ResponseEntity.notFound().build();
        });
    }

    private Map<String, Object> stateFor(Map<String, Object> job) {
        boolean enabled = booleanValue(job.getOrDefault("enabled", true), true);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("job_id", String.valueOf(job.get("id")));
        state.put("enabled", enabled);
        state.put("paused", !enabled);
        state.put("running", false);
        state.put("last_run_at", stringValue(job.get("last_run_at"), ""));
        state.put("next_run_at", stringValue(job.get("next_run_at"), ""));
        state.put("last_run_time", job.getOrDefault("last_run_time", 0));
        state.put("next_run_time", job.getOrDefault("next_run_time", 0));
        return state;
    }

    private void syncScheduledJobs(String agentId, List<Map<String, Object>> jobs) {
        for (Map<String, Object> job : jobs) {
            syncScheduledJob(agentId, job, false);
        }
    }

    private void syncScheduledJob(String agentId, Map<String, Object> job, boolean force) {
        String id = String.valueOf(job.get("id"));
        String runtimeId = runtimeId(agentId, id);
        if (!force && cronManager.get(runtimeId) != null) {
            return;
        }
        cronManager.cancel(runtimeId);
        if (!booleanValue(job.getOrDefault("enabled", true), true)) {
            return;
        }
        CronManager.CronJob cronJob = toCronJob(agentId, job);
        if (cronJob.getMessage() == null || cronJob.getMessage().isBlank()) {
            return;
        }
        cronManager.schedule(cronJob);
    }

    private CronManager.CronJob toCronJob(String agentId, Map<String, Object> job) {
        CronManager.CronJob cronJob = new CronManager.CronJob();
        cronJob.setId(runtimeId(agentId, String.valueOf(job.get("id"))));
        cronJob.setName(stringValue(job.get("name"), "Cron job"));
        cronJob.setAgentId(AgentRequestSupport.agentId(stringValue(job.get("agent_id"), agentId)));
        cronJob.setMessage(promptValue(job));
        cronJob.setEnabled(booleanValue(job.getOrDefault("enabled", true), true));
        Object schedule = job.get("schedule");
        if (schedule instanceof Map<?, ?> map && "once".equalsIgnoreCase(String.valueOf(map.get("type")))) {
            cronJob.setTriggerType(CronManager.TriggerType.ONE_SHOT);
            cronJob.setDelayMs(delayUntil(stringValue(map.get("run_at"), "")));
        } else {
            cronJob.setTriggerType(CronManager.TriggerType.CRON);
            cronJob.setCronExpression(normalizeCronExpression(stringValue(job.get("cron"), stringValue(job.get("cron_expr"), ""))));
        }
        return cronJob;
    }

    private String runtimeId(String agentId, String jobId) {
        return AgentRequestSupport.agentId(agentId) + ":" + jobId;
    }

    private String normalizeCronExpression(String cron) {
        String value = cron == null ? "" : cron.trim().replaceAll("\\s+", " ");
        if (value.split(" ").length == 5) {
            return "0 " + value;
        }
        return value;
    }

    private long delayUntil(String runAt) {
        if (runAt == null || runAt.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Instant.parse(runAt).toEpochMilli() - System.currentTimeMillis());
        } catch (DateTimeParseException ignored) {
            try {
                return Math.max(0, ZonedDateTime.parse(runAt).toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (DateTimeParseException ignoredAgain) {
                return 0;
            }
        }
    }

    private String promptValue(Map<String, Object> job) {
        String direct = stringValue(firstPresent(job, "prompt", "query", "text", "message"), "");
        if (!direct.isBlank()) {
            return direct;
        }
        Object request = job.get("request");
        if (!(request instanceof Map<?, ?> requestMap)) {
            return "";
        }
        Object input = requestMap.get("input");
        if (!(input instanceof List<?> messages)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object message : messages) {
            if (!(message instanceof Map<?, ?> messageMap)) continue;
            Object content = messageMap.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> blockMap && "text".equals(String.valueOf(blockMap.get("type")))) {
                    parts.add(stringValue(blockMap.get("text"), ""));
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void markRun(String agentId, String id, String runAt, String trigger, String status, String error) {
        List<Map<String, Object>> jobs = loadJobs(agentId);
        for (Map<String, Object> job : jobs) {
            if (!CronJobStore.matches(job, id)) {
                continue;
            }
            job.put("last_run_at", runAt);
            job.put("last_run_time", Instant.parse(runAt).toEpochMilli());
            List<Map<String, Object>> history = new ArrayList<>();
            Object raw = job.get("history");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        history.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("run_at", runAt);
            record.put("status", status);
            record.put("trigger", trigger);
            if (error != null && !error.isBlank()) {
                record.put("error", error);
            }
            history.removeIf(item -> runAt.equals(String.valueOf(item.get("run_at"))));
            history.add(0, record);
            if (history.size() > 50) {
                history = new ArrayList<>(history.subList(0, 50));
            }
            job.put("history", history);
            saveJobs(agentId, jobs);
            return;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyFor(Map<String, Object> job) {
        Object raw = job.get("history");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                history.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return history;
    }

    private List<Map<String, Object>> loadJobs(String agentId) {
        return CronJobStore.load(workspaceDir(agentId));
    }

    private void saveJobs(String agentId, List<Map<String, Object>> jobs) {
        CronJobStore.save(workspaceDir(agentId), jobs);
    }

    private Path workspaceDir(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId));
    }

    private Map<String, Object> findJob(String agentId, String id) {
        return CronJobStore.find(workspaceDir(agentId), id);
    }

}
