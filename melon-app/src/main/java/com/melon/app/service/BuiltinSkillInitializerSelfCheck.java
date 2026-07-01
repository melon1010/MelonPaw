package com.melon.app.service;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class BuiltinSkillInitializerSelfCheck {

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-skills-check");
        System.setProperty("user.home", home.toString());

        ConfigManager configManager = new ConfigManager();
        BuiltinSkillInitializer initializer =
                new BuiltinSkillInitializer(configManager, new WorkspaceManager());

        initializer.seedAllAgents();

        Path workspaceSkills = configManager.resolveWorkspaceDir("default").resolve("skills");
        Path pool = home.resolve(".melon").resolve("skill_pool");
        long poolCount;
        try (var stream = Files.list(pool)) {
            poolCount = stream.filter(Files::isDirectory).count();
        }
        if (poolCount < 18) {
            throw new IllegalStateException("Expected builtin pool skills, got " + poolCount + " at " + pool);
        }
        if (!Files.exists(pool.resolve("file_reader").resolve("SKILL.md"))) {
            throw new IllegalStateException("Expected file_reader builtin pool skill");
        }
        long workspaceCount = 0;
        if (Files.exists(workspaceSkills)) {
            try (var stream = Files.list(workspaceSkills)) {
                workspaceCount = stream.filter(Files::isDirectory).count();
            }
        }
        if (workspaceCount != 0) {
            throw new IllegalStateException("Workspace should not be seeded with pool skills: " + workspaceSkills);
        }
        System.out.println(Map.of("poolSkills", poolCount, "pool", pool, "workspaceSkills", workspaceCount));
    }
}
