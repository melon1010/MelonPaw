package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import com.melon.core.security.ScanResult;
import com.melon.core.util.JsonUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

@Service
public class SecuritySettingsService {

    private final ConfigManager configManager;

    public SecuritySettingsService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Map<String, Object> fileGuard() {
        Map<String, Object> guard = section("file_guard", defaultFileGuard());
        guard.putIfAbsent("enabled", false);
        guard.putIfAbsent("paths", List.of());
        guard.putIfAbsent("allow_preview_outside_workspace", false);
        return guard;
    }

    public Map<String, Object> saveFileGuard(Map<String, Object> patch) {
        Map<String, Object> root = load();
        Map<String, Object> guard = new LinkedHashMap<>(fileGuard());
        if (patch != null) guard.putAll(patch);
        guard.put("paths", stringList(guard.get("paths")));
        root.put("file_guard", guard);
        save(root);
        return guard;
    }

    public Map<String, Object> skillScanner() {
        Map<String, Object> scanner = section("skill_scanner", defaultSkillScanner());
        String mode = normalizeMode(scanner.get("mode"));
        scanner.put("mode", mode);
        scanner.putIfAbsent("timeout", 10);
        scanner.put("whitelist", whitelist(scanner.get("whitelist")));
        return scanner;
    }

    public Map<String, Object> saveSkillScanner(Map<String, Object> patch) {
        Map<String, Object> root = load();
        Map<String, Object> scanner = new LinkedHashMap<>(skillScanner());
        if (patch != null) scanner.putAll(patch);
        scanner.put("mode", normalizeMode(scanner.get("mode")));
        scanner.put("whitelist", whitelist(scanner.get("whitelist")));
        root.put("skill_scanner", scanner);
        save(root);
        return scanner;
    }

    public List<Map<String, Object>> blockedHistory() {
        Object raw = load().get("blocked_history");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add(toStringMap(map));
        }
        return result;
    }

    public void clearBlockedHistory() {
        Map<String, Object> root = load();
        root.put("blocked_history", List.of());
        save(root);
    }

    public boolean removeBlockedEntry(int index) {
        List<Map<String, Object>> history = new ArrayList<>(blockedHistory());
        if (index < 0 || index >= history.size()) return false;
        history.remove(index);
        Map<String, Object> root = load();
        root.put("blocked_history", history);
        save(root);
        return true;
    }

    public Map<String, Object> addWhitelist(String skillName, String contentHash) {
        Map<String, Object> scanner = skillScanner();
        List<Map<String, Object>> whitelist = new ArrayList<>(whitelist(scanner.get("whitelist")));
        whitelist.removeIf(item -> skillName.equals(item.get("skill_name")));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("skill_name", skillName);
        entry.put("content_hash", contentHash == null ? "" : contentHash);
        entry.put("added_at", Instant.now().toString());
        whitelist.add(entry);
        scanner.put("whitelist", whitelist);
        saveSkillScanner(scanner);
        return entry;
    }

    public boolean removeWhitelist(String skillName) {
        Map<String, Object> scanner = skillScanner();
        List<Map<String, Object>> whitelist = new ArrayList<>(whitelist(scanner.get("whitelist")));
        boolean removed = whitelist.removeIf(item -> skillName.equals(item.get("skill_name")));
        scanner.put("whitelist", whitelist);
        saveSkillScanner(scanner);
        return removed;
    }

    public Map<String, Object> allowNoAuthHosts() {
        Map<String, Object> hosts = section("allow_no_auth_hosts", Map.of("hosts", List.of()));
        hosts.put("hosts", stringList(hosts.get("hosts")));
        return hosts;
    }

    public Map<String, Object> saveAllowNoAuthHosts(Map<String, Object> patch) {
        Map<String, Object> root = load();
        Map<String, Object> hosts = new LinkedHashMap<>(allowNoAuthHosts());
        if (patch != null) hosts.putAll(patch);
        hosts.put("hosts", stringList(hosts.get("hosts")));
        root.put("allow_no_auth_hosts", hosts);
        save(root);
        return hosts;
    }

    public boolean shouldBlockSkill(String skillName, ScanResult result) {
        if (result == null || result.isSafe()) return false;
        Map<String, Object> scanner = skillScanner();
        if (!"block".equals(scanner.get("mode"))) return false;
        return whitelist(scanner.get("whitelist")).stream()
                .noneMatch(entry -> skillName.equals(entry.get("skill_name")));
    }

    public void recordBlockedSkill(String skillName, ScanResult result, String action) {
        List<Map<String, Object>> history = new ArrayList<>(blockedHistory());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("skill_name", skillName);
        record.put("blocked_at", Instant.now().toString());
        record.put("max_severity", result != null && !result.isSafe() ? "high" : "low");
        record.put("content_hash", "");
        record.put("action", action);
        record.put("findings", findings(result));
        history.add(0, record);
        Map<String, Object> root = load();
        root.put("blocked_history", history);
        save(root);
    }

    private List<Map<String, Object>> findings(ScanResult result) {
        if (result == null) return List.of();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (String issue : result.getIssues()) findings.add(finding("high", issue));
        for (String warning : result.getWarnings()) findings.add(finding("medium", warning));
        return findings;
    }

    private Map<String, Object> finding(String severity, String description) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("title", severity.equals("high") ? "Dangerous skill content" : "Sensitive skill content");
        finding.put("description", description);
        finding.put("file_path", "SKILL.md");
        finding.put("line_number", null);
        finding.put("rule_id", "java-skill-scanner");
        return finding;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String key, Map<String, Object> fallback) {
        Object raw = load().get(key);
        if (raw instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) toStringMap(map));
        return new LinkedHashMap<>(fallback);
    }

    private Map<String, Object> load() {
        return JsonUtils.loadAsMap(file());
    }

    private void save(Map<String, Object> root) {
        JsonUtils.save(file(), root);
    }

    private Path file() {
        return configManager.resolveStateDir().resolve("security.json");
    }

    private Map<String, Object> defaultFileGuard() {
        return Map.of("enabled", false, "paths", List.of(), "allow_preview_outside_workspace", false);
    }

    private Map<String, Object> defaultSkillScanner() {
        return Map.of("mode", "warn", "timeout", 10, "whitelist", List.of());
    }

    private String normalizeMode(Object value) {
        String mode = stringValue(value, "warn").toLowerCase();
        return switch (mode) {
            case "block", "off" -> mode;
            default -> "warn";
        };
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private List<Map<String, Object>> whitelist(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add(toStringMap(map));
            else {
                String name = String.valueOf(item).trim();
                if (!name.isBlank()) result.add(Map.of("skill_name", name, "content_hash", "", "added_at", ""));
            }
        }
        return result;
    }

    private Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
