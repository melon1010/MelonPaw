/**
 * @author melon
 */
package com.melon.app.cli;

import com.melon.core.util.JsonUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 定时任务命令. 对应 Python cli/cron_cmd.py.
 * 支持 list / create / delete crons.
 */
@Command(
    name = "crons",
    description = "Manage cron jobs (list, create, delete)",
    mixinStandardHelpOptions = true,
    subcommands = { CronCommand.ListCrons.class, CronCommand.CreateCron.class, CronCommand.DeleteCron.class }
)
public class CronCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: melon crons <subcommand> [options]");
        System.out.println("Subcommands: list, create, delete");
    }

    static Path getCronsFile() {
        return Path.of(System.getProperty("user.home"), ".melon", "crons.json");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> loadCrons() {
        var raw = JsonUtils.loadAsMap(getCronsFile());
        Object list = raw.get("crons");
        if (list instanceof List) {
            return new ArrayList<>((List<Map<String, Object>>) list);
        }
        return new ArrayList<>();
    }

    static void saveCrons(List<Map<String, Object>> crons) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("crons", crons);
        JsonUtils.save(getCronsFile(), wrapper);
    }

    @Command(name = "list", description = "List all cron jobs", mixinStandardHelpOptions = true)
    static class ListCrons implements Callable<Integer> {
        @Override
        public Integer call() {
            List<Map<String, Object>> crons = loadCrons();
            if (crons.isEmpty()) {
                System.out.println("No cron jobs found.");
                return 0;
            }
            System.out.printf("%-20s %-15s %-10s %s%n", "NAME", "CRON", "ENABLED", "PROMPT");
            System.out.println("-".repeat(70));
            for (Map<String, Object> cron : crons) {
                String name = String.valueOf(cron.get("name"));
                String cronExpr = String.valueOf(cron.get("cron"));
                boolean enabled = Boolean.TRUE.equals(cron.get("enabled"));
                String prompt = String.valueOf(cron.get("prompt"));
                if (prompt.length() > 30) prompt = prompt.substring(0, 30) + "...";
                System.out.printf("%-20s %-15s %-10s %s%n", name, cronExpr, enabled ? "yes" : "no", prompt);
            }
            return 0;
        }
    }

    @Command(name = "create", description = "Create a new cron job", mixinStandardHelpOptions = true)
    static class CreateCron implements Callable<Integer> {

        @Option(names = {"--name"}, required = true, description = "Cron job name")
        String name;

        @Option(names = {"--cron"}, required = true, description = "Cron expression (e.g. '0 9 * * *')")
        String cron;

        @Option(names = {"--prompt"}, required = true, description = "Prompt to execute")
        String prompt;

        @Option(names = {"--disabled"}, description = "Create as disabled")
        boolean disabled;

        @Override
        public Integer call() {
            List<Map<String, Object>> crons = loadCrons();

            // Check for duplicate
            boolean exists = crons.stream().anyMatch(c -> name.equals(c.get("name")));
            if (exists) {
                System.err.println("Error: Cron with name '" + name + "' already exists");
                return 1;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("name", name);
            entry.put("cron", cron);
            entry.put("prompt", prompt);
            entry.put("enabled", !disabled);
            entry.put("created_at", System.currentTimeMillis());

            crons.add(entry);
            saveCrons(crons);

            System.out.println("Cron created: " + name + " (" + cron + ")");
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a cron job", mixinStandardHelpOptions = true)
    static class DeleteCron implements Callable<Integer> {

        @Option(names = {"--name"}, required = true, description = "Cron job name to delete")
        String name;

        @Override
        public Integer call() {
            List<Map<String, Object>> crons = loadCrons();
            boolean removed = crons.removeIf(c -> name.equals(c.get("name")));
            if (!removed) {
                System.err.println("Error: Cron '" + name + "' not found");
                return 1;
            }
            saveCrons(crons);
            System.out.println("Cron deleted: " + name);
            return 0;
        }
    }
}
