package com.melon.app.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MelonCliContractTest {

    @Test
    void rootCommandRegistersMelonPawCommands() {
        CommandLine cli = new CommandLine(new MelonCli());
        assertEquals("melonpaw", cli.getCommandName());
        assertEquals(0, cli.getCommandSpec().aliases().length);
        Set<String> commands = cli.getSubcommands().keySet();
        for (String command : Set.of(
                "app", "init", "agents", "agent", "models", "skills",
                "cron", "crons", "env", "envs", "chats", "chat",
                "channels", "channel", "plugin", "doctor", "clean",
                "shutdown", "task", "acp", "desktop", "auth",
                "daemon", "update", "uninstall", "tui", "auto",
                "workspace", "project", "git", "mcp", "backup",
                "security", "tools", "token-usage", "voice")) {
            assertTrue(commands.contains(command), "missing command: " + command);
        }
    }

    @Test
    void cronAndEnvKeepLegacyPluralAliases() {
        CommandLine cli = new CommandLine(new MelonCli());
        assertTrue(cli.getSubcommands().containsKey("cron"));
        assertTrue(cli.getSubcommands().containsKey("crons"));
        assertTrue(cli.getSubcommands().containsKey("env"));
        assertTrue(cli.getSubcommands().containsKey("envs"));
    }

    @Test
    void outputFormatAcceptsLowercaseCliValues() {
        CommandLine cli = new CommandLine(new MelonCli());
        cli.setOut(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        cli.setErr(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        assertEquals(0, cli.execute("--output", "json", "cron", "--help"));
        assertEquals(0, cli.execute("--output", "table", "env", "--help"));
        assertEquals(0, cli.execute("--output", "plain", "agents", "--help"));
    }

    @Test
    void firstBatchHttpSubcommandsAreRegistered() {
        CommandLine cli = new CommandLine(new MelonCli());
        assertSubcommands(cli, "agents", Set.of("get", "create", "update", "delete", "enable", "disable", "order"));
        assertSubcommands(cli, "models", Set.of("active", "config", "config-key", "set-llm", "test-provider",
                "test-model", "add-provider", "remove-provider", "custom-providers", "add-model", "config-model", "remove-model"));
        assertSubcommands(cli, "skills", Set.of("refresh", "info", "create", "save", "enable", "disable",
                "config", "delete-config", "tags", "channels", "uninstall"));
        assertSubcommands(cli, "plugin", Set.of("catalog", "search", "install", "info", "status", "reload", "uninstall", "validate"));
    }

    @Test
    void secondBatchSubcommandsAreRegistered() {
        CommandLine cli = new CommandLine(new MelonCli());
        assertSubcommands(cli, "channels", Set.of("types", "meta", "get", "config", "start", "stop", "restart",
                "health", "qrcode", "qrcode-status", "access-control", "send"));
        assertSubcommands(cli, "models", Set.of("download", "download-status", "cancel-download", "local", "remove-local"));
        assertSubcommands(cli, "skills", Set.of("install", "install-status", "import-builtin", "test"));
        assertSubcommands(cli, "daemon", Set.of("status", "version", "logs", "reload-config", "restart"));
        assertSubcommands(cli, "doctor", Set.of("fix"));
    }

    @Test
    void consoleBackedCommandsAreRegistered() {
        CommandLine cli = new CommandLine(new MelonCli());
        assertSubcommands(cli, "workspace", Set.of("info", "init", "config", "language", "files",
                "memory", "code-files", "download", "upload", "system-prompt-files"));
        assertSubcommands(cli, "project", Set.of("get", "set", "list", "create", "import-local",
                "upload-zip", "browse-dirs", "clone"));
        assertSubcommands(cli, "git", Set.of("status", "branches", "checkout", "diff", "stage",
                "unstage", "commit", "log", "discard", "commit-diff", "revert"));
        assertSubcommands(cli, "mcp", Set.of("list", "get", "create", "update", "toggle", "delete",
                "tools", "set-tools", "policy", "set-policy", "oauth-start", "oauth-status",
                "oauth-revoke", "reload", "call-tool"));
        assertSubcommands(cli, "backup", Set.of("list", "get", "create", "restore", "delete", "export", "import"));
        assertSubcommands(cli, "security", Set.of("tool-guard", "builtin-rules", "audit-events",
                "file-guard", "skill-scanner", "blocked-history", "whitelist", "allow-no-auth-hosts"));
        assertSubcommands(cli, "tools", Set.of("list", "toggle", "async", "config", "get-config"));
        assertSubcommands(cli, "token-usage", Set.of("summary", "details"));
        assertSubcommands(cli, "voice", Set.of("audio-mode", "provider", "provider-type", "local-whisper-status", "transcribe"));
    }

    @Test
    void secondBatchHelpParses() {
        CommandLine cli = new CommandLine(new MelonCli());
        cli.setOut(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        cli.setErr(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        for (String[] args : java.util.List.of(
                new String[]{"channels", "--help"},
                new String[]{"models", "local", "--help"},
                new String[]{"skills", "install", "--help"},
                new String[]{"daemon", "logs", "--help"},
                new String[]{"task", "--help"},
                new String[]{"shutdown", "--help"},
                new String[]{"workspace", "--help"},
                new String[]{"project", "--help"},
                new String[]{"git", "--help"},
                new String[]{"mcp", "--help"},
                new String[]{"backup", "--help"},
                new String[]{"security", "--help"},
                new String[]{"tools", "--help"},
                new String[]{"token-usage", "--help"},
                new String[]{"voice", "--help"})) {
            assertEquals(0, cli.execute(args));
        }
    }


    @Test
    void policyCommandsAreExecutable() {
        CommandLine cli = new CommandLine(new MelonCli());
        cli.setOut(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        cli.setErr(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        assertEquals(0, cli.execute("update"));
        assertEquals(0, cli.execute("uninstall"));
        assertEquals(0, cli.execute("tui"));
        assertEquals(1, cli.execute("acp"));
    }

    @Test
    void barePathArgsExpandToTuiButUnknownCommandStillFails() {
        CommandLine cli = new CommandLine(new MelonCli());
        cli.setOut(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        cli.setErr(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        assertEquals(java.util.List.of("tui", "."), java.util.List.of(MelonCli.expandBareProjectArgs(new String[]{"."})));
        assertEquals(2, cli.execute("definitely-not-a-command"));
    }

    private void assertSubcommands(CommandLine root, String command, Set<String> expected) {
        Set<String> commands = root.getSubcommands().get(command).getSubcommands().keySet();
        for (String name : expected) {
            assertTrue(commands.contains(name), command + " missing subcommand: " + name);
        }
    }
}
