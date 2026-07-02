package com.melon.core.cron;

import com.melon.core.util.JsonUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Workspace-local QwenPaw-compatible jobs.json storage.
 */
public final class CronJobStore {

    private CronJobStore() {
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load(Path workspaceDir) {
        Object list = JsonUtils.loadAsMap(jobsFile(workspaceDir)).get("jobs");
        if (list instanceof List<?>) {
            return new ArrayList<>((List<Map<String, Object>>) list);
        }
        return new ArrayList<>();
    }

    public static void save(Path workspaceDir, List<Map<String, Object>> jobs) {
        JsonUtils.save(jobsFile(workspaceDir), Map.of("version", 1, "jobs", jobs));
    }

    public static Map<String, Object> find(Path workspaceDir, String id) {
        return load(workspaceDir).stream().filter(job -> matches(job, id)).findFirst().orElse(null);
    }

    public static Map<String, Object> create(Path workspaceDir, Map<String, Object> body) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        Map<String, Object> job = normalize(body, UUID.randomUUID().toString());
        jobs.add(job);
        save(workspaceDir, jobs);
        return job;
    }

    public static Map<String, Object> replace(Path workspaceDir, String id, Map<String, Object> body) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        for (int i = 0; i < jobs.size(); i++) {
            if (matches(jobs.get(i), id)) {
                Map<String, Object> updated = normalize(body, id);
                updated.put("created_at", jobs.get(i).getOrDefault("created_at", Instant.now().toString()));
                jobs.set(i, updated);
                save(workspaceDir, jobs);
                return updated;
            }
        }
        return null;
    }

    public static boolean delete(Path workspaceDir, String id) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        boolean removed = jobs.removeIf(job -> matches(job, id));
        if (removed) {
            save(workspaceDir, jobs);
        }
        return removed;
    }

    public static boolean setEnabled(Path workspaceDir, String id, boolean enabled) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        for (Map<String, Object> job : jobs) {
            if (matches(job, id)) {
                job.put("enabled", enabled);
                job.put("paused", !enabled);
                save(workspaceDir, jobs);
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> normalize(Map<String, Object> body, String id) {
        Map<String, Object> job = new LinkedHashMap<>();
        String name = stringValue(body.get("name"), "Cron job");
        String cron = cronValue(body);
        String prompt = promptValue(body);
        boolean enabled = booleanValue(body.getOrDefault("enabled", true), true);
        job.put("id", stringValue(body.get("id"), id));
        job.put("name", name);
        job.put("cron", cron);
        job.put("cron_expr", cron);
        job.put("prompt", prompt);
        job.put("query", prompt);
        job.put("enabled", enabled);
        job.put("paused", !enabled);
        job.put("schedule", body.getOrDefault("schedule", Map.of("type", "cron", "cron", cron, "timezone", "Asia/Shanghai")));
        job.put("request", body.getOrDefault("request", request(prompt)));
        job.put("dispatch", body.getOrDefault("dispatch", Map.of("type", "channel", "channel", "console", "target", Map.of("user_id", "default"), "mode", "final", "meta", Map.of())));
        job.put("runtime", body.getOrDefault("runtime", Map.of("share_session", true, "max_concurrency", 1, "timeout_seconds", 180, "misfire_grace_seconds", 600)));
        job.put("task_type", stringValue(body.get("task_type"), "agent"));
        job.put("save_result_to_inbox", body.getOrDefault("save_result_to_inbox", true));
        job.put("meta", body.getOrDefault("meta", Map.of()));
        job.put("created_at", stringValue(body.get("created_at"), Instant.now().toString()));
        job.put("updated_at", Instant.now().toString());
        return job;
    }

    public static boolean matches(Map<String, Object> job, String id) {
        return id.equals(String.valueOf(job.get("id"))) || id.equals(String.valueOf(job.get("name")));
    }

    public static Path jobsFile(Path workspaceDir) {
        return workspaceDir.resolve("jobs.json");
    }

    private static Object firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    private static String cronValue(Map<String, Object> body) {
        Object schedule = body.get("schedule");
        if (schedule instanceof Map<?, ?> map && map.get("cron") != null) {
            return String.valueOf(map.get("cron"));
        }
        return stringValue(firstPresent(body, "cron", "cron_expr"), "");
    }

    private static String promptValue(Map<String, Object> body) {
        String direct = stringValue(firstPresent(body, "prompt", "query", "message", "text"), "");
        if (!direct.isBlank()) return direct;
        Object request = body.get("request");
        if (!(request instanceof Map<?, ?> requestMap)) return "";
        Object input = requestMap.get("input");
        if (!(input instanceof List<?> messages)) return "";
        List<String> parts = new ArrayList<>();
        for (Object message : messages) {
            if (!(message instanceof Map<?, ?> messageMap)) continue;
            Object content = messageMap.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> blockMap && "text".equals(String.valueOf(blockMap.get("type")))) {
                    Object text = blockMap.get("text");
                    parts.add(text != null ? String.valueOf(text) : "");
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private static Map<String, Object> request(String prompt) {
        return Map.of("input", List.of(Map.of(
                "role", "user",
                "type", "message",
                "content", List.of(Map.of("type", "text", "text", prompt))
        )));
    }
}
