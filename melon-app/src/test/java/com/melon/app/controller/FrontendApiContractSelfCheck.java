package com.melon.app.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FrontendApiContractSelfCheck {

    private FrontendApiContractSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path controllers = Path.of("melon-app/src/main/java/com/melon/app/controller").toAbsolutePath().normalize();
        String source = readAll(controllers);
        List<String> required = List.of(
                "@RequestMapping(\"/api/models\")",
                "@RequestMapping(\"/api/tools\")",
                "@RequestMapping(\"/api/skills\")",
                "@RequestMapping(\"/api/mcp\")",
                "@RequestMapping(\"/api/workspace\")",
                "@RequestMapping(\"/api/workspace/coding-project\")",
                "@RequestMapping(\"/api/backups\")",
                "@RequestMapping(\"/api/cron\")",
                "@RequestMapping(\"/api/token-usage\")",
                "@RequestMapping(\"/api/chats\")",
                "@RequestMapping(\"/api/console\")",
                "@RequestMapping(\"/api/files\")",
                "@RequestMapping(\"/api/envs\")",
                "@PostMapping(value = \"/chat\"",
                "@PostMapping(\"/chat/stop\")",
                "@PostMapping(value = \"/upload\"",
                "@GetMapping(\"/agent-stats\")",
                "@GetMapping(\"/config/heartbeat\")",
                "@GetMapping(\"/config/security/tool-guard\")",
                "@GetMapping(\"/config/security/file-guard\")",
                "@GetMapping(\"/config/security/skill-scanner\")"
        );
        for (String marker : required) {
            if (!source.contains(marker)) {
                throw new AssertionError("Missing frontend API mapping marker: " + marker);
            }
        }

        try (var stream = Files.walk(controllers)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String text = Files.readString(file);
                if (text.contains("status(501)") && !allowed501(name)) {
                    throw new AssertionError("Unexpected 501 in frontend API controller: " + file);
                }
            }
        }
    }

    private static boolean allowed501(String fileName) {
        return List.of("McpController.java", "PluginController.java", "SkillController.java", "WorkspaceController.java")
                .contains(fileName);
    }

    private static String readAll(Path dir) throws Exception {
        StringBuilder out = new StringBuilder();
        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                out.append(Files.readString(file)).append('\n');
            }
        }
        return out.toString();
    }
}
