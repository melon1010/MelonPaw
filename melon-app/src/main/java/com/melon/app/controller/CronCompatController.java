package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.cron.CronJobStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
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

    public CronCompatController(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @GetMapping("/jobs")
    public Mono<ResponseEntity<?>> listJobs(@RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(loadJobs(agentId)));
    }

    @PostMapping("/jobs")
    public Mono<ResponseEntity<?>> createJob(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            Map<String, Object> job = CronJobStore.normalize(body, UUID.randomUUID().toString());
            jobs.add(job);
            saveJobs(agentId, jobs);
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
            view.put("history", List.of());
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
            return ResponseEntity.ok(Map.of(
                    "started", false,
                    "job_id", String.valueOf(job.get("id")),
                    "detail", "Cron dispatch is not running in Java compatibility mode"
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
    public Mono<ResponseEntity<?>> history(@PathVariable String id) {
        return Mono.just(ResponseEntity.ok(List.of()));
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
                    return ResponseEntity.noContent().build();
                }
            }
            return ResponseEntity.notFound().build();
        });
    }

    private Map<String, Object> stateFor(Map<String, Object> job) {
        boolean enabled = booleanValue(job.getOrDefault("enabled", true), true);
        return Map.of(
                "job_id", String.valueOf(job.get("id")),
                "enabled", enabled,
                "paused", !enabled,
                "running", false,
                "last_run_at", "",
                "next_run_at", ""
        );
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
