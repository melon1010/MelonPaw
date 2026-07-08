package com.melon.app.controller;

import com.melon.app.cron.CronExecutor;
import com.melon.app.cron.CronManager;
import com.melon.app.service.InboxStore;
import com.melon.channels.ChannelConfigService;
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
 * melonPaw frontend-compatible cron API aliases.
 */
@RestController
@RequestMapping("/api/cron")
public class CronCompatController {

    private final ConfigManager configManager;
    private final CronManager cronManager;
    private final CronExecutor cronExecutor;
    private final ChannelConfigService channelConfigService;
    private final InboxStore inboxStore;

    public CronCompatController(ConfigManager configManager,
                                CronManager cronManager,
                                CronExecutor cronExecutor,
                                ChannelConfigService channelConfigService,
                                InboxStore inboxStore) {
        this.configManager = configManager;
        this.cronManager = cronManager;
        this.cronExecutor = cronExecutor;
        this.channelConfigService = channelConfigService;
        this.inboxStore = inboxStore;
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
            String runId = "cron-" + AgentRequestSupport.agentId(agentId) + ":" + taskId;
            markRun(agentId, id, runAt, "manual", "running", null);
            if (booleanValue(job.getOrDefault("save_result_to_inbox", true), true)) {
                inboxStore.createTrace(runId, Map.of("job_id", jobId, "job_name", stringValue(job.get("name"), "Cron job")));
            }
            cronExecutor.injectDispatchedMessage(AgentRequestSupport.agentId(agentId), prompt, runId, dispatch(job),
                    reply -> {
                        markRun(agentId, id, runAt, "manual", "success", null);
                        appendManualInboxEvent(agentId, job, runId, cleanText(reply), null);
                    },
                    err -> {
                        markRun(agentId, id, runAt, "manual", "error", err.getMessage());
                        appendManualInboxEvent(agentId, job, runId, "", err);
                    });
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
    public Mono<ResponseEntity<?>> dispatchTargets(@RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Map<String, Object>> channels = channelConfigService.list(AgentRequestSupport.agentId(agentId));
            List<String> enabled = channels.entrySet().stream()
                    .filter(entry -> booleanValue(entry.getValue().get("enabled"), false))
                    .map(Map.Entry::getKey)
                    .toList();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> job : loadJobs(agentId)) {
                Object dispatch = job.get("dispatch");
                if (!(dispatch instanceof Map<?, ?> dispatchMap)) continue;
                Object target = dispatchMap.get("target");
                if (!(target instanceof Map<?, ?> targetMap)) continue;
                String channel = stringValue(dispatchMap.get("channel"), "console");
                items.add(Map.of(
                        "channel", channel,
                        "user_id", stringValue(targetMap.get("user_id"), "default"),
                        "session_id", stringValue(targetMap.get("session_id"), "cron_job")
                ));
            }
            return ResponseEntity.ok(Map.of(
                    "channels", enabled,
                    "items", items,
                    "enabled", true
            ));
        });
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
        cronJob.setSourceId(String.valueOf(job.get("id")));
        cronJob.setName(stringValue(job.get("name"), "Cron job"));
        cronJob.setAgentId(AgentRequestSupport.agentId(stringValue(job.get("agent_id"), agentId)));
        cronJob.setMessage(promptValue(job));
        cronJob.setEnabled(booleanValue(job.getOrDefault("enabled", true), true));
        cronJob.setSaveResultToInbox(booleanValue(job.getOrDefault("save_result_to_inbox", true), true));
        cronJob.setTaskType(stringValue(job.get("task_type"), "agent"));
        cronJob.setDispatch(dispatch(job));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatch(Map<String, Object> job) {
        Object raw = job.get("dispatch");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return Map.of("type", "channel", "channel", "console", "target", Map.of("user_id", "default", "session_id", "cron_job"), "mode", "final", "meta", Map.of());
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

    private void appendManualInboxEvent(String agentId, Map<String, Object> job, String runId, String text, Throwable err) {
        if (!booleanValue(job.getOrDefault("save_result_to_inbox", true), true)) {
            return;
        }
        boolean ok = err == null;
        inboxStore.appendTraceText(runId, "assistant", ok ? text : err.getMessage());
        inboxStore.finalizeTrace(runId, ok ? "success" : "error", ok ? null : err.getMessage());
        String jobId = String.valueOf(job.get("id"));
        String name = stringValue(job.get("name"), "Cron job");
        inboxStore.appendEvent(
                AgentRequestSupport.agentId(agentId),
                "cron",
                jobId,
                ok ? "cron_result" : "cron_error",
                ok ? "success" : "error",
                ok ? "info" : "error",
                (ok ? "Cron result: " : "Cron failed: ") + name,
                preview(ok ? text : err.getMessage(), ok ? "Agent cron task finished successfully." : "Cron task failed."),
                Map.of("job_id", jobId, "job_name", name, "task_type", stringValue(job.get("task_type"), "agent"), "trigger", "manual", "run_id", runId)
        );
    }

    private String preview(String value, String fallback) {
        String text = stringValue(value, fallback).trim();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String cleanText(com.melon.channels.ChannelOutboundMessage out) {
        if (out == null) return "";
        Object visible = out.getMeta().get("visible_text");
        String text = visible != null ? String.valueOf(visible) : "";
        return text.isBlank() ? out.getText() : text;
    }

}
