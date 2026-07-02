package com.melon.app.service;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SkillZipImportSelfCheck {

    private SkillZipImportSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-skill-zip-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        SecuritySettingsService security = new SecuritySettingsService(configManager);
        SkillService service = new SkillService(configManager, new WorkspaceManager(), security);

        Path zip = home.resolve("skills.zip");
        writeZip(zip, Map.of(
                "zip-skill/SKILL.md", """
                        ---
                        description: imported from zip
                        ---

                        # Zip Skill
                        """,
                "zip-skill/references/info.md", "reference"
        ));

        Map<String, Object> workspaceResult = service.importWorkspaceSkillZip("default", zip, false, "", Map.of());
        if (!workspaceResult.get("imported").toString().contains("zip-skill")
                || !Files.exists(home.resolve("workspaces/default/skills/zip-skill/SKILL.md"))
                || !Files.exists(home.resolve("workspaces/default/skills/zip-skill/.disabled"))) {
            throw new AssertionError("workspace zip import failed: " + workspaceResult);
        }

        Map<String, Object> conflict = service.importWorkspaceSkillZip("default", zip, true, "", Map.of());
        if (!conflict.toString().contains("conflicts") || !conflict.toString().contains("suggested_name")) {
            throw new AssertionError("workspace zip conflict was not reported: " + conflict);
        }

        Map<String, Object> renamed = service.importPoolSkillZip(zip, "", Map.of("zip-skill", "pool-zip-skill"));
        if (!renamed.get("imported").toString().contains("pool-zip-skill")
                || !Files.exists(home.resolve("skill_pool/pool-zip-skill/SKILL.md"))) {
            throw new AssertionError("pool zip import with rename failed: " + renamed);
        }

        Path unicodeZip = home.resolve("unicode-skills.zip");
        writeZip(unicodeZip, Map.of(
                "中文技能/SKILL.md", """
                        ---
                        description: 中文技能
                        ---

                        # 中文技能
                        """
        ));
        Map<String, Object> unicodeResult = service.importPoolSkillZip(unicodeZip, "", Map.of());
        if (!unicodeResult.get("imported").toString().contains("中文技能")
                || !Files.exists(home.resolve("skill_pool/中文技能/SKILL.md"))) {
            throw new AssertionError("unicode skill import failed: " + unicodeResult);
        }

        Path renamedHome = Files.createTempDirectory("melon-skill-zip-rename-check");
        ConfigManager renamedConfig = new ConfigManager();
        renamedConfig.setHomeDir(renamedHome.toString());
        SkillService renamedService = new SkillService(renamedConfig, new WorkspaceManager(), new SecuritySettingsService(renamedConfig));
        Map<String, Object> unicodeRenamed = renamedService.importPoolSkillZip(unicodeZip, "", Map.of("中文技能", "中文技能-导入"));
        if (!unicodeRenamed.get("imported").toString().contains("中文技能-导入")
                || !Files.exists(renamedHome.resolve("skill_pool/中文技能-导入/SKILL.md"))) {
            throw new AssertionError("unicode skill rename failed: " + unicodeRenamed);
        }

        security.saveSkillScanner(Map.of("mode", "block"));
        Path dangerousZip = home.resolve("dangerous.zip");
        writeZip(dangerousZip, Map.of(
                "danger/SKILL.md", "# Danger\n",
                "danger/run.sh", "rm -rf /tmp/danger\n"
        ));
        try {
            service.importPoolSkillZip(dangerousZip, "", Map.of());
            throw new AssertionError("dangerous zip import was not blocked");
        } catch (java.io.IOException expected) {
            if (!expected.getMessage().contains("security scan")) {
                throw expected;
            }
        }
        if (security.blockedHistory().isEmpty()) {
            throw new AssertionError("blocked zip import was not recorded");
        }
    }

    private static void writeZip(Path path, Map<String, String> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
