/**
 * @author melon
 */
package com.melon.app.service;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service for skill management.
 * Handles skill discovery, creation, and deletion.
 * Corresponds to Python skill management endpoints.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);
    private static final String DEFAULT_AGENT_ID = "default";

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;

    public SkillService(ConfigManager configManager, WorkspaceManager workspaceManager) {
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
    }

    /**
     * Lists all available skills.
     */
    public List<Map<String, Object>> listSkills() {
        return listSkills(DEFAULT_AGENT_ID);
    }

    public List<Map<String, Object>> listSkills(String agentId) {
        return listSkillDir(skillsDir(agentId));
    }

    public List<Map<String, Object>> listPoolSkills() {
        return listSkillDir(skillPoolDir());
    }

    private List<Map<String, Object>> listSkillDir(Path skillsDir) {
        List<Map<String, Object>> skills = new ArrayList<>();
        if (!Files.exists(skillsDir)) {
            return skills;
        }
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                Path skillFile = dir.resolve("SKILL.md");
                String description = "";
                String content = "";
                if (Files.exists(skillFile)) {
                    try {
                        content = Files.readString(skillFile);
                        description = extractDescription(content);
                    } catch (IOException ignored) {}
                }
                Map<String, Object> meta = meta(dir);
                Map<String, Object> skill = new LinkedHashMap<>();
                skill.put("name", name);
                skill.put("description", description);
                skill.put("path", dir.toString());
                skill.put("content", content);
                skill.put("enabled", !Files.exists(dir.resolve(".disabled")));
                skill.put("source", meta.getOrDefault("source", "local"));
                skill.put("channels", stringList(meta.get("channels")));
                skill.put("tags", stringList(meta.get("tags")));
                skill.put("config", mapValue(meta.get("config")));
                try {
                    skill.put("last_updated", Files.getLastModifiedTime(skillFile).toInstant().toString());
                } catch (IOException ignored) {
                    skill.put("last_updated", "");
                }
                skills.add(skill);
            });
        } catch (IOException e) {
            log.error("Failed to list skills", e);
        }
        return skills;
    }

    /**
     * Gets skill content by name.
     */
    public String getSkillContent(String skillName) throws IOException {
        return getSkillContent(DEFAULT_AGENT_ID, skillName);
    }

    public String getSkillContent(String agentId, String skillName) throws IOException {
        Path skillsDir = skillsDir(agentId);
        Path skillFile = skillsDir.resolve(skillName).resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            throw new NoSuchFileException("Skill not found: " + skillName);
        }
        return Files.readString(skillFile);
    }

    /**
     * Creates a new skill.
     */
    public void createSkill(String name, String content) throws IOException {
        createSkill(DEFAULT_AGENT_ID, name, content);
    }

    public void createSkill(String agentId, String name, String content) throws IOException {
        Path skillsDir = skillsDir(agentId);
        writeSkill(skillsDir.resolve(name), content);
        setEnabled(skillsDir.resolve(name), true);
        log.info("Skill created: {}", name);
    }

    public void createPoolSkill(String name, String content) throws IOException {
        writeSkill(skillPoolDir().resolve(name), content);
        log.info("Pool skill created: {}", name);
    }

    /**
     * Deletes a skill.
     */
    public void deleteSkill(String name) throws IOException {
        deleteSkill(DEFAULT_AGENT_ID, name);
    }

    public void deleteSkill(String agentId, String name) throws IOException {
        Path skillsDir = skillsDir(agentId);
        deleteSkillDir(skillsDir.resolve(name));
    }

    public void deletePoolSkill(String name) throws IOException {
        deleteSkillDir(skillPoolDir().resolve(name));
    }

    public Map<String, Object> enableSkill(String agentId, String name) throws IOException {
        Path skillDir = requireSkillDir(skillsDir(agentId), name);
        setEnabled(skillDir, true);
        return Map.of("success", true, "enabled", true, "name", name);
    }

    public Map<String, Object> disableSkill(String agentId, String name) throws IOException {
        Path skillDir = requireSkillDir(skillsDir(agentId), name);
        setEnabled(skillDir, false);
        return Map.of("success", true, "disabled", true, "name", name);
    }

    public boolean setSkillTags(String agentId, String name, List<String> tags) throws IOException {
        Path skillDir = requireSkillDir(skillsDir(agentId), name);
        updateMeta(skillDir, "tags", tags == null ? List.of() : tags);
        return true;
    }

    public boolean setPoolSkillTags(String name, List<String> tags) throws IOException {
        Path skillDir = requireSkillDir(skillPoolDir(), name);
        updateMeta(skillDir, "tags", tags == null ? List.of() : tags);
        return true;
    }

    public boolean setSkillChannels(String agentId, String name, List<String> channels) throws IOException {
        Path skillDir = requireSkillDir(skillsDir(agentId), name);
        updateMeta(skillDir, "channels", channels == null ? List.of() : channels);
        return true;
    }

    public Map<String, Object> getSkillConfig(String agentId, String name) throws IOException {
        return mapValue(meta(requireSkillDir(skillsDir(agentId), name)).get("config"));
    }

    public void setSkillConfig(String agentId, String name, Map<String, Object> config) throws IOException {
        updateMeta(requireSkillDir(skillsDir(agentId), name), "config", config == null ? Map.of() : config);
    }

    public void clearSkillConfig(String agentId, String name) throws IOException {
        updateMeta(requireSkillDir(skillsDir(agentId), name), "config", Map.of());
    }

    public Map<String, Object> getPoolSkillConfig(String name) throws IOException {
        return mapValue(meta(requireSkillDir(skillPoolDir(), name)).get("config"));
    }

    public void setPoolSkillConfig(String name, Map<String, Object> config) throws IOException {
        updateMeta(requireSkillDir(skillPoolDir(), name), "config", config == null ? Map.of() : config);
    }

    public void clearPoolSkillConfig(String name) throws IOException {
        updateMeta(requireSkillDir(skillPoolDir(), name), "config", Map.of());
    }

    public Map<String, Object> downloadPoolSkillToWorkspace(String agentId, String name, boolean overwrite) throws IOException {
        Path source = skillPoolDir().resolve(name);
        if (!Files.exists(source.resolve("SKILL.md"))) {
            throw new NoSuchFileException("Pool skill not found: " + name);
        }
        Path target = skillsDir(agentId).resolve(name);
        if (Files.exists(target) && !overwrite) {
            return Map.of(
                    "downloaded", false,
                    "conflict", true,
                    "name", name,
                    "workspace_id", safeAgentId(agentId)
            );
        }
        if (Files.exists(target)) {
            deleteSkillDir(target);
        }
        copyDirectory(source, target);
        return Map.of(
                "downloaded", true,
                "name", name,
                "workspace_id", safeAgentId(agentId),
                "workspace_name", safeAgentId(agentId)
        );
    }

    public Map<String, Object> uploadWorkspaceSkillToPool(String agentId, String name, boolean overwrite) throws IOException {
        Path source = skillsDir(agentId).resolve(name);
        if (!Files.exists(source.resolve("SKILL.md"))) {
            throw new NoSuchFileException("Workspace skill not found: " + name);
        }
        Path target = skillPoolDir().resolve(name);
        if (Files.exists(target) && !overwrite) {
            return Map.of("success", false, "conflict", true, "name", name);
        }
        if (Files.exists(target)) {
            deleteSkillDir(target);
        }
        copyDirectory(source, target);
        return Map.of("success", true, "name", name);
    }

    public Path skillPoolDir() {
        Path dir = configManager.resolveSkillPoolDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create skill pool", e);
        }
        return dir;
    }

    public Path workspaceDir(String agentId) {
        return configManager.resolveWorkspaceDir(safeAgentId(agentId));
    }

    public Set<String> agentIds() {
        return configManager.getConfig().getAgents().keySet();
    }

    public AgentConfig agentConfig(String agentId) {
        return configManager.getConfig().getAgent(safeAgentId(agentId));
    }

    private void writeSkill(Path skillDir, String content) throws IOException {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }

    private void setEnabled(Path skillDir, boolean enabled) throws IOException {
        Path marker = skillDir.resolve(".disabled");
        if (enabled) {
            Files.deleteIfExists(marker);
        } else {
            Files.createDirectories(skillDir);
            Files.writeString(marker, "");
        }
    }

    private void deleteSkillDir(Path skillDir) throws IOException {
        if (Files.exists(skillDir)) {
            try (var stream = Files.walk(skillDir)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try { Files.delete(p); } catch (IOException ignored) {}
                      });
            }
            log.info("Skill deleted: {}", skillDir.getFileName());
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path dst = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(path, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String extractDescription(String content) {
        // Extract description from YAML frontmatter
        int descIdx = content.indexOf("description:");
        if (descIdx >= 0) {
            int lineEnd = content.indexOf('\n', descIdx);
            if (lineEnd > descIdx) {
                return content.substring(descIdx + 12, lineEnd).trim();
            }
        }
        return "";
    }

    private Path requireSkillDir(Path parent, String name) throws IOException {
        Path skillDir = parent.resolve(name).normalize();
        if (!skillDir.startsWith(parent.normalize()) || !Files.exists(skillDir.resolve("SKILL.md"))) {
            throw new NoSuchFileException("Skill not found: " + name);
        }
        return skillDir;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> meta(Path skillDir) {
        Path path = skillDir.resolve(".melon-skill-meta.json");
        Map<String, Object> raw = com.melon.core.util.JsonUtils.loadAsMap(path);
        return raw == null ? new LinkedHashMap<>() : raw;
    }

    private void updateMeta(Path skillDir, String key, Object value) {
        Map<String, Object> meta = new LinkedHashMap<>(meta(skillDir));
        meta.put(key, value);
        com.melon.core.util.JsonUtils.save(skillDir.resolve(".melon-skill-meta.json"), meta);
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

    private Path skillsDir(String agentId) {
        Path workspace = configManager.resolveWorkspaceDir(safeAgentId(agentId));
        workspaceManager.initWorkspace(workspace);
        return workspace.resolve("skills");
    }

    private String safeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? DEFAULT_AGENT_ID : agentId;
    }
}
