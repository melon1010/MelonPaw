package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Python-compatible builtin skill pool operations.
 */
@Service
public class BuiltinSkillService {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillService.class);
    private static final String RESOURCE_ROOT = "builtin-skills";
    private static final String RESOURCE_PATTERN = "classpath*:" + RESOURCE_ROOT + "/*/SKILL.md";
    private static final String LANGUAGE = "default";

    private final ConfigManager configManager;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public BuiltinSkillService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void seedPool() {
        try {
            Path pool = poolDir();
            boolean firstRun = !Files.exists(manifestPath());
            boolean emptyPool = !hasAnySkillDir(pool);
            if (firstRun || emptyPool) {
                importBuiltins(null, true);
            } else {
                ensureManifestBaseline();
            }
        } catch (Exception e) {
            log.warn("Failed to seed builtin skill pool: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listBuiltinSources() throws IOException {
        Map<String, BuiltinSkill> registry = registry();
        Map<String, Object> manifest = readManifest();
        Map<String, Object> skills = mapValue(manifest.get("skills"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (BuiltinSkill skill : registry.values()) {
            result.add(candidate(skill, skills));
        }
        return result;
    }

    public Map<String, Object> builtinNotice() throws IOException {
        Map<String, BuiltinSkill> registry = registry();
        Map<String, Object> manifest = readManifest();
        Map<String, Object> skills = mapValue(manifest.get("skills"));
        Set<String> previous = new TreeSet<>(stringList(manifest.get("builtin_skill_names")));
        Set<String> current = new TreeSet<>(registry.keySet());

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();

        for (String name : current) {
            BuiltinSkill skill = registry.get(name);
            Map<String, Object> item = candidate(skill, skills);
            String status = str(item.get("status"));
            if (!previous.contains(name)) {
                added.add(item);
            } else if ("missing".equals(status)) {
                missing.add(item);
            } else if (!"current".equals(status)) {
                updated.add(item);
            }
        }

        for (String name : previous) {
            if (current.contains(name)) {
                continue;
            }
            Map<String, Object> entry = mapValue(skills.get(name));
            if (!entry.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("description", str(entry.get("description")));
                item.put("current_version_text", str(entry.get("version_text")));
                item.put("current_source", str(entry.get("source")));
                removed.add(item);
            }
        }

        Set<String> actionable = new TreeSet<>();
        for (Map<String, Object> item : concat(added, missing, updated)) {
            if (!"current".equals(str(item.get("status")))) {
                actionable.add(str(item.get("name")));
            }
        }
        int total = added.size() + missing.size() + updated.size() + removed.size();
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("fingerprint", total == 0 ? "" : fingerprint(added, missing, updated, removed));
        notice.put("has_updates", total > 0);
        notice.put("total_changes", total);
        notice.put("actionable_skill_names", new ArrayList<>(actionable));
        notice.put("added", added);
        notice.put("missing", missing);
        notice.put("updated", updated);
        notice.put("removed", removed);
        return notice;
    }

    public Map<String, Object> importBuiltins(List<Map<String, Object>> imports, boolean overwriteConflicts) throws IOException {
        Map<String, BuiltinSkill> registry = registry();
        Map<String, Object> manifest = readManifest();
        Map<String, Object> skills = mapValue(manifest.get("skills"));
        boolean defaultAll = imports == null;
        List<String> selected = normalizeSelections(imports, registry);
        if (selected.isEmpty() && defaultAll) {
            selected.addAll(registry.keySet());
        }

        List<Map<String, Object>> conflicts = conflicts(selected, registry, skills);
        if (!conflicts.isEmpty() && !overwriteConflicts) {
            return importResult(List.of(), List.of(), List.of(), conflicts);
        }

        List<String> imported = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (String name : selected) {
            BuiltinSkill builtin = registry.get(name);
            Path target = poolDir().resolve(name).normalize();
            Map<String, Object> existing = mapValue(skills.get(name));
            boolean exists = Files.exists(target.resolve("SKILL.md"));
            boolean current = exists
                    && "builtin".equals(str(existing.get("source")))
                    && builtin.versionText().equals(str(existing.get("version_text")));
            if (current) {
                unchanged.add(name);
            } else {
                if (exists) {
                    deleteDirectory(target);
                    updated.add(name);
                } else {
                    imported.add(name);
                }
                copyBuiltin(name, target);
            }
            Map<String, Object> next = metadata(name, target, builtin, existing);
            skills.put(name, next);
            JsonUtils.save(target.resolve(".melon-skill-meta.json"), next);
        }
        manifest.put("schema_version", "skill-pool-manifest.v1");
        manifest.put("version", numberValue(manifest.get("version")) + 1);
        manifest.put("skills", skills);
        manifest.put("builtin_skill_names", new ArrayList<>(registry.keySet()));
        JsonUtils.save(manifestPath(), manifest);
        return importResult(imported, updated, unchanged, List.of());
    }

    public Map<String, Object> updateBuiltin(String name, String language) throws IOException {
        Map<String, BuiltinSkill> registry = registry();
        String canonical = canonicalName(name, registry);
        BuiltinSkill builtin = registry.get(canonical);
        if (builtin == null) {
            throw new NoSuchFileException("'" + name + "' is not a builtin skill");
        }
        Map<String, Object> manifest = readManifest();
        Map<String, Object> skills = mapValue(manifest.get("skills"));
        Map<String, Object> existing = mapValue(skills.get(canonical));
        if (!"builtin".equals(str(existing.get("source")))) {
            throw new NoSuchFileException("'" + canonical + "' is not a builtin pool skill");
        }
        Path target = poolDir().resolve(canonical).normalize();
        if (Files.exists(target)) {
            deleteDirectory(target);
        }
        copyBuiltin(canonical, target);
        Map<String, Object> next = metadata(canonical, target, builtin, existing);
        skills.put(canonical, next);
        manifest.put("schema_version", "skill-pool-manifest.v1");
        manifest.put("version", numberValue(manifest.get("version")) + 1);
        manifest.put("skills", skills);
        manifest.put("builtin_skill_names", new ArrayList<>(registry.keySet()));
        JsonUtils.save(target.resolve(".melon-skill-meta.json"), next);
        JsonUtils.save(manifestPath(), manifest);
        Map<String, Object> result = new LinkedHashMap<>(next);
        result.put("updated", true);
        result.put("language", normalizeLanguage(language));
        return result;
    }

    public Map<String, Object> syncInfo(String name, Map<String, Object> raw) {
        try {
            Map<String, BuiltinSkill> registry = registry();
            BuiltinSkill builtin = registry.get(name);
            if (!"builtin".equals(str(raw.get("source")))) {
                return Map.of();
            }
            if (builtin == null) {
                return Map.of("sync_status", "outdated", "latest_version_text", "", "available_languages", List.of());
            }
            String current = str(raw.get("version_text"));
            if (current.equals(builtin.versionText())) {
                return Map.of("sync_status", "synced", "latest_version_text", "", "available_languages", List.of(LANGUAGE));
            }
            return Map.of("sync_status", "outdated", "latest_version_text", builtin.versionText(), "available_languages", List.of(LANGUAGE));
        } catch (IOException e) {
            return Map.of();
        }
    }

    private void ensureManifestBaseline() throws IOException {
        Map<String, BuiltinSkill> registry = registry();
        Map<String, Object> manifest = readManifest();
        if (!stringList(manifest.get("builtin_skill_names")).isEmpty()) {
            return;
        }
        manifest.put("schema_version", "skill-pool-manifest.v1");
        manifest.put("version", numberValue(manifest.get("version")) + 1);
        manifest.put("builtin_skill_names", new ArrayList<>(registry.keySet()));
        manifest.putIfAbsent("skills", new LinkedHashMap<String, Object>());
        JsonUtils.save(manifestPath(), manifest);
    }

    private Map<String, Object> candidate(BuiltinSkill builtin, Map<String, Object> skills) {
        Map<String, Object> current = mapValue(skills.get(builtin.name()));
        if (current.isEmpty()) {
            current = readMeta(poolDir().resolve(builtin.name()));
        }
        String currentSource = str(current.get("source"));
        String currentVersion = str(current.get("version_text"));
        String status;
        if (current.isEmpty() || !Files.exists(poolDir().resolve(builtin.name()).resolve("SKILL.md"))) {
            status = "missing";
        } else if (!"builtin".equals(currentSource)) {
            status = "conflict";
        } else if (currentVersion.equals(builtin.versionText())) {
            status = "current";
        } else if (!currentVersion.isBlank() && !builtin.versionText().isBlank()) {
            status = "outdated";
        } else {
            status = "conflict";
        }

        Map<String, Object> lang = new LinkedHashMap<>();
        lang.put("language", LANGUAGE);
        lang.put("description", builtin.description());
        lang.put("version_text", builtin.versionText());
        lang.put("source_name", builtin.name());
        lang.put("status", status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", builtin.name());
        result.put("description", builtin.description());
        result.put("version_text", builtin.versionText());
        result.put("current_version_text", currentVersion);
        result.put("current_source", currentSource);
        result.put("current_language", str(current.get("builtin_language")));
        result.put("available_languages", List.of(LANGUAGE));
        result.put("languages", Map.of(LANGUAGE, lang));
        result.put("status", status);
        return result;
    }

    private List<Map<String, Object>> conflicts(List<String> selected, Map<String, BuiltinSkill> registry,
                                                Map<String, Object> skills) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String name : selected) {
            Map<String, Object> candidate = candidate(registry.get(name), skills);
            String status = str(candidate.get("status"));
            String source = str(candidate.get("current_source"));
            if (source.isBlank() || (!"conflict".equals(status) && !"outdated".equals(status))) {
                continue;
            }
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("skill_name", name);
            conflict.put("language", LANGUAGE);
            conflict.put("status", status);
            conflict.put("source_name", name);
            conflict.put("source_version_text", str(candidate.get("version_text")));
            conflict.put("current_version_text", str(candidate.get("current_version_text")));
            conflict.put("current_source", source);
            conflict.put("current_language", str(candidate.get("current_language")));
            conflicts.add(conflict);
        }
        return conflicts;
    }

    private Map<String, Object> metadata(String name, Path target, BuiltinSkill builtin, Map<String, Object> existing) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("description", builtin.description());
        meta.put("version_text", builtin.versionText());
        meta.put("commit_text", "");
        meta.put("source", "builtin");
        meta.put("protected", false);
        meta.put("builtin_language", LANGUAGE);
        meta.put("builtin_source_name", builtin.name());
        meta.put("available_builtin_languages", List.of(LANGUAGE));
        meta.put("requirements", Map.of());
        meta.put("updated_at", Instant.now().toString());
        if (existing.containsKey("config")) {
            meta.put("config", existing.get("config"));
        }
        if (existing.containsKey("tags")) {
            meta.put("tags", existing.get("tags"));
        }
        if (!meta.containsKey("config")) {
            meta.put("config", Map.of());
        }
        if (!meta.containsKey("tags")) {
            meta.put("tags", List.of());
        }
        try {
            if (Files.exists(target.resolve("SKILL.md"))) {
                meta.put("updated_at", Files.getLastModifiedTime(target.resolve("SKILL.md")).toInstant().toString());
            }
        } catch (IOException ignored) {
        }
        return meta;
    }

    private Map<String, BuiltinSkill> registry() throws IOException {
        Map<String, BuiltinSkill> registry = new LinkedHashMap<>();
        List<Resource> markers = new ArrayList<>(List.of(resolver.getResources(RESOURCE_PATTERN)));
        markers.sort(Comparator.comparing(resource -> {
            try {
                return resource.getURL().toString();
            } catch (IOException e) {
                return "";
            }
        }));
        for (Resource marker : markers) {
            String name = skillName(marker);
            if (name == null || name.isBlank()) {
                continue;
            }
            String content;
            try (InputStream in = marker.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            registry.put(name, new BuiltinSkill(name, extractDescription(content), extractVersion(content)));
        }
        return registry;
    }

    private void copyBuiltin(String skillName, Path target) throws IOException {
        Files.createDirectories(target);
        String pattern = "classpath*:" + RESOURCE_ROOT + "/" + skillName + "/**/*";
        String prefix = "/" + RESOURCE_ROOT + "/" + skillName + "/";
        for (Resource resource : resolver.getResources(pattern)) {
            if (!resource.isReadable()) {
                continue;
            }
            String url = resource.getURL().toString();
            int idx = url.indexOf(prefix);
            if (idx < 0) {
                continue;
            }
            String relative = url.substring(idx + prefix.length());
            if (relative.isBlank() || relative.endsWith("/")) {
                continue;
            }
            Path dst = target.resolve(relative).normalize();
            if (!dst.startsWith(target.normalize())) {
                throw new IOException("Unsafe builtin skill resource path: " + relative);
            }
            Files.createDirectories(dst.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private List<String> normalizeSelections(List<Map<String, Object>> imports, Map<String, BuiltinSkill> registry) throws IOException {
        List<String> selected = new ArrayList<>();
        if (imports == null) {
            return selected;
        }
        for (Map<String, Object> item : imports) {
            String raw = str(item.getOrDefault("skill_name", item.get("source_name"))).trim();
            String name = canonicalName(raw, registry);
            if (!registry.containsKey(name)) {
                throw new NoSuchFileException("Unknown builtin skill: " + raw);
            }
            if (!selected.contains(name)) {
                selected.add(name);
            }
        }
        return selected;
    }

    private String canonicalName(String raw, Map<String, BuiltinSkill> registry) {
        String name = str(raw).trim();
        if (registry.containsKey(name)) {
            return name;
        }
        return name;
    }

    private Map<String, Object> readManifest() {
        Map<String, Object> manifest = JsonUtils.loadAsMap(manifestPath());
        if (manifest.isEmpty()) {
            manifest.put("schema_version", "skill-pool-manifest.v1");
            manifest.put("version", 0);
            manifest.put("skills", new LinkedHashMap<String, Object>());
            manifest.put("builtin_skill_names", List.of());
        }
        manifest.putIfAbsent("skills", new LinkedHashMap<String, Object>());
        manifest.putIfAbsent("builtin_skill_names", List.of());
        return manifest;
    }

    private Path poolDir() {
        Path pool = configManager.resolveSkillPoolDir();
        try {
            Files.createDirectories(pool);
        } catch (IOException e) {
            log.warn("Failed to create skill pool {}: {}", pool, e.getMessage());
        }
        return pool;
    }

    private Path manifestPath() {
        return poolDir().resolve("skill.json");
    }

    private Map<String, Object> readMeta(Path dir) {
        return JsonUtils.loadAsMap(dir.resolve(".melon-skill-meta.json"));
    }

    private boolean hasAnySkillDir(Path pool) throws IOException {
        if (!Files.isDirectory(pool)) {
            return false;
        }
        try (var stream = Files.list(pool)) {
            return stream.anyMatch(path -> Files.exists(path.resolve("SKILL.md")));
        }
    }

    private String skillName(Resource marker) throws IOException {
        String url = marker.getURL().toString();
        int idx = url.lastIndexOf("/SKILL.md");
        if (idx < 0) {
            return null;
        }
        int slash = url.lastIndexOf('/', idx - 1);
        return slash >= 0 ? url.substring(slash + 1, idx) : null;
    }

    private String extractDescription(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("description:")) {
                return unquote(trimmed.substring("description:".length()).trim());
            }
        }
        return "";
    }

    private String extractVersion(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            for (String key : List.of("builtin_skill_version:", "version:")) {
                if (trimmed.startsWith(key)) {
                    return unquote(trimmed.substring(key.length()).trim());
                }
            }
        }
        return "";
    }

    private String unquote(String value) {
        String result = value == null ? "" : value.trim();
        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Map<String, Object> importResult(List<String> imported, List<String> updated,
                                             List<String> unchanged, List<Map<String, Object>> conflicts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("unchanged", unchanged);
        result.put("conflicts", conflicts);
        return result;
    }

    @SafeVarargs
    private List<Map<String, Object>> concat(List<Map<String, Object>>... lists) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Map<String, Object>> list : lists) {
            result.addAll(list);
        }
        return result;
    }

    private String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(JsonUtils.toJson(List.of(values)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(str(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeLanguage(String language) {
        return LANGUAGE;
    }

    private record BuiltinSkill(String name, String description, String versionText) {
    }
}
