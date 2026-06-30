/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * QwenPaw frontend-compatible cron API aliases.
 */
@RestController
@RequestMapping("/api/cron")
public class CronCompatController {

    private final Path cronsFile;

    public CronCompatController(ConfigManager configManager) {
        this.cronsFile = configManager.resolveHomeDir().resolve("crons.json");
    }

    @GetMapping("/jobs")
    public Mono<ResponseEntity<?>> listJobs() {
        return Mono.fromCallable(() -> ResponseEntity.ok(loadJobs()));
    }

    @PostMapping("/jobs")
    public Mono<ResponseEntity<?>> createJob(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs();
            Map<String, Object> job = normalizeJob(body, UUID.randomUUID().toString());
            jobs.add(job);
            saveJobs(jobs);
            return ResponseEntity.ok(job);
        });
    }

    @GetMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> getJob(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(id);
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
    public Mono<ResponseEntity<?>> replaceJob(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs();
            for (int i = 0; i < jobs.size(); i++) {
                if (matchesJob(jobs.get(i), id)) {
                    Map<String, Object> updated = normalizeJob(body, id);
                    updated.put("created_at", jobs.get(i).getOrDefault("created_at", Instant.now().toString()));
                    jobs.set(i, updated);
                    saveJobs(jobs);
                    return ResponseEntity.ok(updated);
                }
            }
            return ResponseEntity.notFound().build();
        });
    }

    @DeleteMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> deleteJob(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs();
            boolean removed = jobs.removeIf(job -> matchesJob(job, id));
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            saveJobs(jobs);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/jobs/{id}/pause")
    public Mono<ResponseEntity<?>> pauseJob(@PathVariable String id) {
        return setEnabled(id, false);
    }

    @PostMapping("/jobs/{id}/resume")
    public Mono<ResponseEntity<?>> resumeJob(@PathVariable String id) {
        return setEnabled(id, true);
    }

    @PostMapping("/jobs/{id}/run")
    public Mono<ResponseEntity<?>> runJob(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(id);
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
    public Mono<ResponseEntity<?>> jobState(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(id);
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

    private Mono<ResponseEntity<?>> setEnabled(String id, boolean enabled) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs();
            for (Map<String, Object> job : jobs) {
                if (matchesJob(job, id)) {
                    job.put("enabled", enabled);
                    job.put("paused", !enabled);
                    saveJobs(jobs);
                    return ResponseEntity.noContent().build();
                }
            }
            return ResponseEntity.notFound().build();
        });
    }

    private Map<String, Object> normalizeJob(Map<String, Object> body, String id) {
        Map<String, Object> job = new LinkedHashMap<>();
        String name = stringValue(body.get("name"), "Cron job");
        String cron = stringValue(firstPresent(body, "cron", "cron_expr", "schedule"), "");
        String prompt = stringValue(firstPresent(body, "prompt", "query", "message"), "");
        boolean enabled = booleanValue(body.getOrDefault("enabled", true));
        job.put("id", stringValue(body.get("id"), id));
        job.put("name", name);
        job.put("cron", cron);
        job.put("cron_expr", cron);
        job.put("prompt", prompt);
        job.put("query", prompt);
        job.put("enabled", enabled);
        job.put("paused", !enabled);
        job.put("channels", body.getOrDefault("channels", List.of()));
        job.put("dispatch_targets", body.getOrDefault("dispatch_targets", List.of()));
        job.put("created_at", stringValue(body.get("created_at"), Instant.now().toString()));
        job.put("updated_at", Instant.now().toString());
        return job;
    }

    private Map<String, Object> stateFor(Map<String, Object> job) {
        boolean enabled = booleanValue(job.getOrDefault("enabled", true));
        return Map.of(
                "job_id", String.valueOf(job.get("id")),
                "enabled", enabled,
                "paused", !enabled,
                "running", false,
                "last_run_at", "",
                "next_run_at", ""
        );
    }

    private Object firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadJobs() {
        Object list = JsonUtils.loadAsMap(cronsFile).get("crons");
        if (list instanceof List<?>) {
            return new ArrayList<>((List<Map<String, Object>>) list);
        }
        return new ArrayList<>();
    }

    private void saveJobs(List<Map<String, Object>> jobs) {
        JsonUtils.save(cronsFile, Map.of("crons", jobs));
    }

    private Map<String, Object> findJob(String id) {
        return loadJobs().stream().filter(job -> matchesJob(job, id)).findFirst().orElse(null);
    }

    private boolean matchesJob(Map<String, Object> job, String id) {
        return id.equals(String.valueOf(job.get("id"))) || id.equals(String.valueOf(job.get("name")));
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }
}
