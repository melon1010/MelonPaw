package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "tui", description = "Open the terminal chat UI", mixinStandardHelpOptions = true)
public class TuiCommand implements Callable<Integer> {
    @Spec CommandSpec commandSpec;
    @Option(names = "--agent", defaultValue = "default", description = "Agent ID to chat with")
    String agent;
    @Option(names = "--resume", description = "Resume an existing session ID")
    String resume;
    @Option(names = "--user-id", defaultValue = "default", description = "User ID")
    String userId;
    @Option(names = "--task-timeout", defaultValue = "300", description = "Task timeout in seconds")
    double taskTimeout;
    @Option(names = "--poll-interval", defaultValue = "1", description = "Poll interval in seconds")
    double pollInterval;
    @Parameters(index = "0", arity = "0..1", paramLabel = "PROJECT")
    String project;

    @Override
    public Integer call() {
        try {
            return run(commandSpec, agent, resume, userId, project, taskTimeout, pollInterval);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(commandSpec.commandLine(), e.getMessage());
        }
    }

    static int runDefault(CommandSpec commandSpec) {
        return runDefault(commandSpec, null);
    }

    static int runDefault(CommandSpec commandSpec, String project) {
        return run(commandSpec, "default", null, "default", project, 300, 1);
    }

    private static int run(CommandSpec commandSpec, String agent, String resume, String userId, String project,
                           double taskTimeout, double pollInterval) {
        Path projectDir = resolveProjectDir(project);
        String sessionId = resume == null || resume.isBlank()
                ? "tui-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : resume.trim();
        String agentId = agent == null || agent.isBlank() ? "default" : agent.trim();
        String uid = userId == null || userId.isBlank() ? "default" : userId.trim();

        System.out.println("MelonPaw TUI");
        System.out.println("agent=" + agentId + " session=" + sessionId + " project=" + projectDir);
        System.out.println("Type /help for commands, /exit to quit.");
        if (System.console() == null && !hasPipedInput()) {
            return 0;
        }

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        while (true) {
            System.out.print("> ");
            System.out.flush();
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("/exit".equals(line) || "/quit".equals(line)) {
                break;
            }
            if ("/help".equals(line)) {
                System.out.println("/session             show current session");
                System.out.println("/resume <session>   switch session");
                System.out.println("/exit               quit");
                continue;
            }
            if ("/session".equals(line)) {
                System.out.println(sessionId);
                continue;
            }
            if (line.startsWith("/resume ")) {
                sessionId = line.substring("/resume ".length()).trim();
                System.out.println("session=" + sessionId);
                continue;
            }
            TaskCommand.submitAndPoll(commandSpec, agentId, uid, sessionId, line, taskTimeout, pollInterval, false,
                    requestContext(agentId, sessionId, projectDir));
        }

        System.out.println("Bye! To resume this session, run: melonpaw tui --agent "
                + agentId + " --resume " + sessionId + " " + quote(projectDir.toString()));
        return 0;
    }

    static Map<String, Object> requestContext(String agentId, String sessionId, Path projectDir) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("root_agent_id", agentId);
        context.put("root_session_id", sessionId);
        context.put("project_dir", projectDir.toString());
        context.put("working_dir", projectDir.toString());
        context.put("source", "tui");
        return context;
    }

    private static Path resolveProjectDir(String project) {
        Path path = project == null || project.isBlank() ? Path.of("") : Path.of(project);
        path = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Project path is not a directory: " + path);
        }
        return path;
    }

    static boolean looksLikeProjectPath(String value) {
        if (value == null || value.isBlank() || value.startsWith("-")) {
            return false;
        }
        return ".".equals(value)
                || "..".equals(value)
                || value.contains("/")
                || value.contains("\\")
                || Files.isDirectory(Path.of(value).toAbsolutePath().normalize());
    }

    private static String quote(String value) {
        return value.contains(" ") ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
    }

    private static boolean hasPipedInput() {
        try {
            return System.in.available() > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
