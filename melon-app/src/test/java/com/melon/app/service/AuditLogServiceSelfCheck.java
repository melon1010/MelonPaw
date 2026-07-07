package com.melon.app.service;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public final class AuditLogServiceSelfCheck {

    private AuditLogServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-audit-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        AuditLogService audit = new AuditLogService(configManager);

        audit.recordApproval("default", "s1", "execute", Map.of("command", "pwd"), true, "approved by user", Map.of("request_id", "r1"));
        if (!Files.isRegularFile(home.resolve("workspaces/default/governance/audit.db"))) {
            throw new AssertionError("audit.db was not created");
        }
        if (audit.query("default", "s1", "execute", "allow", 10).size() != 1) {
            throw new AssertionError("audit query failed");
        }
        if (audit.purge("default", Instant.now().plusSeconds(60).toEpochMilli()) != 1) {
            throw new AssertionError("audit purge failed");
        }
    }
}
