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
        new WorkspaceManager().initWorkspace(dir);

        for (String file : List.of("AGENTS.md", "BOOTSTRAP.md", "HEARTBEAT.md", "MEMORY.md", "PROFILE.md", "SOUL.md", "jobs.json", "chats.json", "agent.json")) {
            if (!Files.exists(dir.resolve(file))) {
                throw new AssertionError("missing workspace file: " + file);
            }
        }
        for (String subdir : List.of("sessions", "memory", "skills", "active_skills", "customized_skills", "browser/user_data", "jobs_history", "dialog", "tool_results", "uploads")) {
            if (!Files.isDirectory(dir.resolve(subdir))) {
                throw new AssertionError("missing workspace directory: " + subdir);
            }
        }
    }
}
