package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import com.melon.core.security.ScanResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SecuritySettingsServiceSelfCheck {

    private SecuritySettingsServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-security-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        SecuritySettingsService service = new SecuritySettingsService(configManager);

        Map<String, Object> fileGuard = service.saveFileGuard(Map.of(
                "enabled", true,
                "paths", java.util.List.of("/tmp", "/tmp", " ")
        ));
        if (!Boolean.TRUE.equals(fileGuard.get("enabled"))
                || !java.util.List.of("/tmp").equals(fileGuard.get("paths"))) {
            throw new AssertionError("file guard did not persist normalized paths: " + fileGuard);
        }

        ScanResult unsafe = new ScanResult("danger");
        unsafe.addIssue("rm -rf /");

        service.saveSkillScanner(Map.of("mode", "warn"));
        if (service.shouldBlockSkill("danger", unsafe)) {
            throw new AssertionError("warn mode should not block");
        }
        service.recordBlockedSkill("danger", unsafe, "warned");
        if (service.blockedHistory().isEmpty()) {
            throw new AssertionError("warned skill was not recorded");
        }

        service.saveSkillScanner(Map.of("mode", "block"));
        if (!service.shouldBlockSkill("danger", unsafe)) {
            throw new AssertionError("block mode should block unsafe skill");
        }
        service.addWhitelist("danger", "");
        if (service.shouldBlockSkill("danger", unsafe)) {
            throw new AssertionError("whitelisted skill should not block");
        }

        service.saveSkillScanner(Map.of("mode", "off"));
        if (service.shouldBlockSkill("other", unsafe)) {
            throw new AssertionError("off mode should not block");
        }
    }
}
