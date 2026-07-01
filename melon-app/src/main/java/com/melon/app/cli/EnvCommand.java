package com.melon.app.cli;

import com.melon.core.util.JsonUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 环境变量命令. 对应 Python cli/env_cmd.py.
 * 支持 list / get / set envs.
 */
@Command(
    name = "envs",
    description = "Manage environment variables (list, get, set)",
    mixinStandardHelpOptions = true,
    subcommands = { EnvCommand.ListEnvs.class, EnvCommand.GetEnv.class, EnvCommand.SetEnv.class, EnvCommand.DeleteEnv.class }
)
public class EnvCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: melon envs <subcommand> [options]");
        System.out.println("Subcommands: list, get, set, delete");
    }

    static Path getEnvsFile() {
        return Path.of(System.getProperty("user.home"), ".melon", "envs.json");
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> loadEnvs() {
        var raw = JsonUtils.loadAsMap(getEnvsFile());
        Map<String, String> envs = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            envs.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return envs;
    }

    static void saveEnvs(Map<String, String> envs) {
        JsonUtils.save(getEnvsFile(), envs);
    }

    static String maskValue(String key, String value) {
        if (value == null) return null;
        String lowerKey = key.toLowerCase();
        if (lowerKey.contains("key") || lowerKey.contains("secret")
                || lowerKey.contains("password") || lowerKey.contains("token")) {
            if (value.length() <= 8) return "****";
            return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        }
        return value;
    }

    @Command(name = "list", description = "List all environment variables", mixinStandardHelpOptions = true)
    static class ListEnvs implements Callable<Integer> {

        @Option(names = {"--show-all"}, description = "Show full values (do not mask secrets)")
        boolean showAll;

        @Override
        public Integer call() {
            Map<String, String> envs = loadEnvs();
            if (envs.isEmpty()) {
                System.out.println("No environment variables set.");
                return 0;
            }
            System.out.printf("%-25s %s%n", "KEY", "VALUE");
            System.out.println("-".repeat(60));
            for (var entry : envs.entrySet()) {
                String value = showAll ? entry.getValue() : maskValue(entry.getKey(), entry.getValue());
                System.out.printf("%-25s %s%n", entry.getKey(), value);
            }
            System.out.println("\nTotal: " + envs.size() + " variables");
            return 0;
        }
    }

    @Command(name = "get", description = "Get a single environment variable", mixinStandardHelpOptions = true)
    static class GetEnv implements Callable<Integer> {

        @Option(names = {"--key"}, required = true, description = "Variable key")
        String key;

        @Override
        public Integer call() {
            Map<String, String> envs = loadEnvs();
            String value = envs.get(key);
            if (value == null) {
                System.err.println("Error: Variable '" + key + "' not found");
                return 1;
            }
            System.out.println(key + "=" + value);
            return 0;
        }
    }

    @Command(name = "set", description = "Set an environment variable", mixinStandardHelpOptions = true)
    static class SetEnv implements Callable<Integer> {

        @Option(names = {"--key"}, required = true, description = "Variable key")
        String key;

        @Option(names = {"--value"}, required = true, description = "Variable value")
        String value;

        @Override
        public Integer call() {
            Map<String, String> envs = loadEnvs();
            envs.put(key, value);
            saveEnvs(envs);
            System.out.println("Set: " + key + "=" + maskValue(key, value));
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete an environment variable", mixinStandardHelpOptions = true)
    static class DeleteEnv implements Callable<Integer> {

        @Option(names = {"--key"}, required = true, description = "Variable key to delete")
        String key;

        @Override
        public Integer call() {
            Map<String, String> envs = loadEnvs();
            if (envs.remove(key) == null) {
                System.err.println("Error: Variable '" + key + "' not found");
                return 1;
            }
            saveEnvs(envs);
            System.out.println("Deleted: " + key);
            return 0;
        }
    }
}
