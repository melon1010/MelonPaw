/**
 * @author melon
 */
package com.melon.app.service;

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

    private final Path skillsDir;

    public SkillService() {
        this.skillsDir = Path.of(System.getProperty("user.home"), ".melon", "skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("Failed to create skills directory: {}", skillsDir);
        }
    }

    /**
     * Lists all available skills.
     */
    public List<Map<String, String>> listSkills() {
        List<Map<String, String>> skills = new ArrayList<>();
        if (!Files.exists(skillsDir)) {
            return skills;
        }
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                Path skillFile = dir.resolve("SKILL.md");
                String description = "";
                if (Files.exists(skillFile)) {
                    try {
                        String content = Files.readString(skillFile);
                        description = extractDescription(content);
                    } catch (IOException ignored) {}
                }
                skills.add(Map.of(
                    "name", name,
                    "description", description,
                    "path", dir.toString()
                ));
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
        Path skillDir = skillsDir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
        log.info("Skill created: {}", name);
    }

    /**
     * Deletes a skill.
     */
    public void deleteSkill(String name) throws IOException {
        Path skillDir = skillsDir.resolve(name);
        if (Files.exists(skillDir)) {
            try (var stream = Files.walk(skillDir)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try { Files.delete(p); } catch (IOException ignored) {}
                      });
            }
            log.info("Skill deleted: {}", name);
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
}
