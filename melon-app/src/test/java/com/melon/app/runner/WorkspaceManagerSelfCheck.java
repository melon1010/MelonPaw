package com.melon.app.runner;

import com.melon.core.agent.WorkspaceManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class WorkspaceManagerSelfCheck {

    private WorkspaceManagerSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("melon-workspace-check");
        WorkspaceManager workspaceManager = new WorkspaceManager();
        workspaceManager.initWorkspace(dir);

        for (String file : List.of("AGENTS.md", "BOOTSTRAP.md", "HEARTBEAT.md", "MEMORY.md", "PROFILE.md", "SOUL.md", "jobs.json", "chats.json", "agent.json", "skill.json", "credentials.yaml")) {
            if (!Files.exists(dir.resolve(file))) {
                throw new AssertionError("missing workspace file: " + file);
            }
        }
        for (String subdir : List.of("sessions/console", "media", "tool_results", "skills", "memory", "resource", "mem_session", "mem_metadata", "digest", "drivers", ".mcp", "browser")) {
            if (!Files.isDirectory(dir.resolve(subdir))) {
                throw new AssertionError("missing workspace directory: " + subdir);
            }
        }
        for (String oldDir : List.of("active_skills", "customized_skills", "uploads", "knowledge", "default", "agents")) {
            if (Files.exists(dir.resolve(oldDir))) {
                throw new AssertionError("unexpected non-Python default directory: " + oldDir);
            }
        }

        Path legacyDir = Files.createTempDirectory("melon-workspace-legacy-check");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("AGENTS.md"), "# Melon Agent Instructions\n\nold builtin\n");
        Files.writeString(legacyDir.resolve("PROFILE.md"), "# My Custom Profile\n\nkeep this\n");
        workspaceManager.initWorkspace(legacyDir);
        String migratedAgents = Files.readString(legacyDir.resolve("AGENTS.md"));
        if (!migratedAgents.startsWith("---\nsummary: \"AGENTS.md 工作区模板\"")) {
            throw new AssertionError("legacy AGENTS.md was not migrated: " + migratedAgents);
        }
        String customProfile = Files.readString(legacyDir.resolve("PROFILE.md"));
        if (!customProfile.startsWith("# My Custom Profile")) {
            throw new AssertionError("custom PROFILE.md should not be overwritten: " + customProfile);
        }

        Path root = Files.createTempDirectory("melon-workspace-delete-root");
        Path target = root.resolve("agent-a");
        Files.createDirectories(target.resolve("nested"));
        Files.writeString(target.resolve("nested/file.txt"), "delete me");
        if (!workspaceManager.deleteWorkspaceIfUnderRoot(target, root) || Files.exists(target)) {
            throw new AssertionError("workspace under root was not deleted");
        }
        Path outside = Files.createTempDirectory("melon-workspace-delete-outside");
        Files.writeString(outside.resolve("keep.txt"), "keep me");
        if (workspaceManager.deleteWorkspaceIfUnderRoot(outside, root) || !Files.exists(outside.resolve("keep.txt"))) {
            throw new AssertionError("workspace outside root should not be deleted");
        }
    }
}
