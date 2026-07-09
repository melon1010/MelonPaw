package com.melon.app.cli;

import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.context.CliOptionResolver;
import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "shutdown", description = "Stop running Melon app processes", mixinStandardHelpOptions = true)
public class ShutdownCommand extends AbstractHttpCommand implements Callable<Integer> {
    @Option(names = "--force", description = "Kill process listening on the configured port") boolean force;

    @Override
    public Integer call() {
        if (!force) {
            return execute(CliCommandSpecs.SHUTDOWN);
        }
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        try {
            List<String> pids = pidsOnPort(context.port());
            if (pids.isEmpty()) {
                System.out.println("No process found on port " + context.port());
                return 0;
            }
            for (String pid : pids) {
                new ProcessBuilder(killCommand(pid)).inheritIO().start().waitFor();
                System.out.println("Killed process " + pid);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Force shutdown failed: " + e.getMessage());
            return 1;
        }
    }

    private List<String> pidsOnPort(int port) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) return windowsPids(port);
        Process process = new ProcessBuilder("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t").start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            List<String> pids = reader.lines().map(String::trim).filter(line -> !line.isBlank()).distinct().toList();
            process.waitFor();
            return pids;
        }
    }

    private List<String> windowsPids(int port) throws Exception {
        Process process = new ProcessBuilder("netstat", "-ano", "-p", "tcp").start();
        List<String> pids = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            reader.lines().filter(line -> line.contains(":" + port) && line.contains("LISTENING"))
                    .map(line -> line.trim().replaceAll(".*\\s+(\\d+)$", "$1"))
                    .forEach(pids::add);
        }
        process.waitFor();
        return pids.stream().distinct().toList();
    }

    private List<String> killCommand(String pid) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("taskkill", "/PID", pid, "/F") : List.of("kill", "-TERM", pid);
    }
}
