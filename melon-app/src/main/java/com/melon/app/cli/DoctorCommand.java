package com.melon.app.cli;

import com.melon.app.cli.context.CliOptionResolver;
import com.melon.app.cli.context.CliOptions;
import com.melon.core.util.PlatformUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI command to run a health check.
 * Checks Java version, OS, disk space, config file existence, and port availability.
 */
@Command(name = "doctor", description = "Health check", mixinStandardHelpOptions = true, subcommands = DoctorCommand.Fix.class)
public class DoctorCommand implements Callable<Integer> {

    @Spec
    CommandSpec commandSpec;

    @Override
    public Integer call() {
        CliOptions options = CliOptionResolver.from(commandSpec);
        System.out.println("Melon Health Check");
        System.out.println("====================");
        System.out.println();

        boolean allOk = true;

        // 1. Java version
        String javaVersion = System.getProperty("java.version");
        int majorVersion = Runtime.version().feature();
        boolean javaOk = majorVersion >= 17;
        System.out.printf("[%s] Java Version: %s%n", javaOk ? "OK  " : "FAIL", javaVersion);
        if (!javaOk) {
            System.out.println("       Java 17 or later is required.");
            allOk = false;
        }

        // 2. OS
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        System.out.printf("[OK  ] OS: %s (%s)%n", osName, osArch);

        // 3. Shell
        String shell = PlatformUtil.getDefaultShell();
        System.out.printf("[OK  ] Shell: %s%n", shell);

        // 4. Disk space
        try {
            Path checkPath = Path.of(System.getProperty("user.home"));
            FileStore store = Files.getFileStore(checkPath);
            long usable = store.getUsableSpace();
            long total = store.getTotalSpace();
            System.out.printf("[OK  ] Disk Space: %s available of %s%n",
                    formatBytes(usable), formatBytes(total));
            if (usable < 1024L * 1024 * 1024) {
                System.out.println("       Warning: Less than 1 GB of disk space available.");
            }
        } catch (IOException e) {
            System.out.printf("[WARN] Disk Space: Unable to check (%s)%n", e.getMessage());
        }

        // 5. Config file
        com.melon.app.cli.paths.CliPathResolver paths = new com.melon.app.cli.paths.CliPathResolver();
        Path configPath = paths.configPath();
        boolean configExists = Files.exists(configPath);
        System.out.printf("[%s] Config File: %s%n",
                configExists ? "OK  " : "FAIL", configPath);
        if (!configExists) {
            System.out.println("       Run 'melonpaw init' to create configuration.");
            allOk = false;
        }

        // 6. Sessions directory
        Path sessionsDir = paths.resolveUnderHome("sessions");
        boolean sessionsExists = Files.exists(sessionsDir);
        System.out.printf("[%s] Sessions Dir: %s%n",
                sessionsExists ? "OK  " : "WARN", sessionsDir);
        if (!sessionsExists) {
            System.out.println("       Will be created automatically on first use.");
        }

        // 7. Skills directory
        Path skillsDir = paths.resolveUnderHome("skills");
        boolean skillsExists = Files.exists(skillsDir);
        System.out.printf("[%s] Skills Dir: %s%n",
                skillsExists ? "OK  " : "WARN", skillsDir);
        if (!skillsExists) {
            System.out.println("       Will be created automatically on first use.");
        }

        // 8. Port availability
        int port = options.getPort();
        boolean portAvailable = false;
        try (ServerSocket socket = new ServerSocket(port)) {
            portAvailable = true;
        } catch (IOException e) {
            portAvailable = false;
        }
        System.out.printf("[%s] Port %d: %s%n",
                portAvailable ? "OK  " : "WARN", port,
                portAvailable ? "Available" : "In use (server may be running)");
        if (!portAvailable) {
            System.out.println("       Use --port <port> to check a different port.");
        }

        System.out.println();
        if (allOk) {
            System.out.println("All critical checks passed.");
        } else {
            System.out.println("Some checks failed. See messages above.");
        }
        return allOk ? 0 : 1;
    }


    @Command(name = "fix", description = "Create missing local CLI directories", mixinStandardHelpOptions = true)
    static class Fix implements Callable<Integer> {
        @Override
        public Integer call() {
            com.melon.app.cli.paths.CliPathResolver paths = new com.melon.app.cli.paths.CliPathResolver();
            try {
                Files.createDirectories(paths.cacheDir());
                Files.createDirectories(paths.logDir());
                Files.createDirectories(paths.workspaceDir("default"));
                Files.createDirectories(paths.resolveUnderHome("sessions"));
                Files.createDirectories(paths.resolveUnderHome("skills"));
                if (!Files.exists(paths.configPath())) {
                    Files.writeString(paths.configPath(), "# MelonPaw CLI config\n");
                }
                System.out.println("Fixed local paths under " + paths.homeDir());
                return 0;
            } catch (IOException e) {
                System.err.println("Fix failed: " + e.getMessage());
                return 1;
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
