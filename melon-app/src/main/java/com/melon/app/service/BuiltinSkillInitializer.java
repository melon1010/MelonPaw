package com.melon.app.service;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Seeds bundled QwenPaw skills into the shared skill pool.
 */
@Service
public class BuiltinSkillInitializer {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillInitializer.class);
    private static final String RESOURCE_ROOT = "builtin-skills";
    private static final String RESOURCE_PATTERN = "classpath*:" + RESOURCE_ROOT + "/*/SKILL.md";
    private static final String ALL_RESOURCES_PATTERN = "classpath*:" + RESOURCE_ROOT + "/**/*";
    private static final String VERSION_KEY = "builtin_skill_version:";

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public BuiltinSkillInitializer(ConfigManager configManager, WorkspaceManager workspaceManager) {
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
    }

    public void seedAllAgents() {
        seedPool();
    }

    public void seedPool() {
        Path pool = skillPoolDir();
        try {
            for (Resource marker : resolver.getResources(RESOURCE_PATTERN)) {
                String skillName = skillName(marker);
                if (skillName == null || skillName.isBlank()) {
                    continue;
                }
                Path target = pool.resolve(skillName);
                if (Files.exists(target.resolve("SKILL.md")) && !needsUpdate(marker, target.resolve("SKILL.md"))) {
                    continue;
                }
                copySkill(skillName, target);
                log.info("Seeded builtin skill '{}' into pool {}", skillName, target);
            }
        } catch (IOException e) {
            log.warn("Failed to seed builtin skills into pool: {}", e.getMessage());
        }
    }

    private Path skillPoolDir() {
        Path pool = configManager.resolveSkillPoolDir();
        try {
            Files.createDirectories(pool);
        } catch (IOException e) {
            log.warn("Failed to create skill pool {}: {}", pool, e.getMessage());
        }
        return pool;
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

    private void copySkill(String skillName, Path target) throws IOException {
        String prefix = "/" + RESOURCE_ROOT + "/" + skillName + "/";
        for (Resource resource : resolver.getResources(ALL_RESOURCES_PATTERN)) {
            String url = resource.getURL().toString();
            int idx = url.indexOf(prefix);
            if (idx < 0 || !resource.isReadable()) {
                continue;
            }
            String relative = url.substring(idx + prefix.length());
            if (relative.isBlank() || relative.endsWith("/")) {
                continue;
            }
            Path dst = target.resolve(relative);
            if (Files.exists(dst) && !"SKILL.md".equals(relative)) {
                continue;
            }
            Files.createDirectories(dst.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private boolean needsUpdate(Resource source, Path target) throws IOException {
        String current = Files.readString(target);
        String next;
        try (InputStream in = source.getInputStream()) {
            next = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String currentVersion = builtinVersion(current);
        String nextVersion = builtinVersion(next);
        return !nextVersion.isBlank() && !nextVersion.equals(currentVersion);
    }

    private String builtinVersion(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(VERSION_KEY)) {
                return trimmed.substring(VERSION_KEY.length()).trim().replace("\"", "");
            }
        }
        return "";
    }
}
