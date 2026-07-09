package com.melon.app.cli;

import com.melon.app.cli.paths.CliPathResolver;
import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "daemon", description = "Daemon commands", mixinStandardHelpOptions = true,
        subcommands = {DaemonCommand.Status.class, DaemonCommand.Restart.class, DaemonCommand.ReloadConfig.class,
                DaemonCommand.Version.class, DaemonCommand.Logs.class})
public class DaemonCommand implements Runnable {
    public void run() { System.out.println("Usage: melonpaw daemon <subcommand> [options]"); }

    @Command(name = "status", mixinStandardHelpOptions = true)
    static class Status implements Callable<Integer> {
        public Integer call() {
            CliPathResolver paths = new CliPathResolver();
            System.out.println("home=" + paths.homeDir());
            System.out.println("config=" + paths.configPath() + " exists=" + Files.isRegularFile(paths.configPath()));
            System.out.println("logs=" + paths.logDir());
            System.out.println("process=external (use melonpaw app to start)");
            return 0;
        }
    }

    @Command(name = "restart", mixinStandardHelpOptions = true)
    static class Restart implements Callable<Integer> {
        public Integer call() {
            System.out.println("Restart is not managed by the CLI. Stop the current process, then run: melonpaw app");
            return 0;
        }
    }

    @Command(name = "reload-config", mixinStandardHelpOptions = true)
    static class ReloadConfig implements Callable<Integer> {
        public Integer call() {
            System.out.println("Config reload is handled on app start. Restart with: melonpaw app");
            return 0;
        }
    }

    @Command(name = "version", mixinStandardHelpOptions = true)
    static class Version implements Callable<Integer> {
        public Integer call() {
            String version = MelonCli.class.getPackage().getImplementationVersion();
            System.out.println(version != null ? version : "dev");
            return 0;
        }
    }

    @Command(name = "logs", mixinStandardHelpOptions = true)
    static class Logs extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--lines", defaultValue = "200") int lines;
        @Option(names = {"--backend", "--remote"}, description = "Read logs from a running backend") boolean backend;
        public Integer call() {
            if (backend) {
                return execute(CliCommandSpecs.BACKEND_LOGS, java.util.Map.of("lines", String.valueOf(lines)), null);
            }
            Path log = new CliPathResolver().logDir().resolve("melon.log");
            if (!Files.isRegularFile(log)) {
                System.out.println("No log file: " + log);
                return 0;
            }
            try {
                List<String> all = Files.readAllLines(log);
                int from = Math.max(0, all.size() - Math.max(0, lines));
                all.subList(from, all.size()).forEach(System.out::println);
                return 0;
            } catch (Exception e) {
                System.err.println("Cannot read log: " + e.getMessage());
                return 1;
            }
        }
    }
}
