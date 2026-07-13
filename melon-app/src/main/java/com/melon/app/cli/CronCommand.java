package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "cron",
    aliases = "crons",
    description = "Manage scheduled cron jobs",
    mixinStandardHelpOptions = true,
    subcommands = {
            CronCommand.ListJobs.class,
            CronCommand.GetJob.class,
            CronCommand.StateJob.class,
            CronCommand.HistoryJob.class,
            CronCommand.DispatchTargets.class,
            CronCommand.CreateJob.class,
            CronCommand.UpdateJob.class,
            CronCommand.DeleteJob.class,
            CronCommand.PauseJob.class,
            CronCommand.ResumeJob.class,
            CronCommand.RunJob.class
    }
)
public class CronCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: melonpaw cron <subcommand> [options]");
        System.out.println("Subcommands: list, get, state, history, dispatch-targets, create, update, delete, pause, resume, run");
    }

    @Command(name = "list", description = "List all cron jobs", mixinStandardHelpOptions = true)
    static class ListJobs extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_LIST); }
    }

    @Command(name = "get", description = "Fetch a cron job by ID", mixinStandardHelpOptions = true)
    static class GetJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_GET, Map.of("id", id), null); }
    }

    @Command(name = "state", description = "Get cron runtime state", mixinStandardHelpOptions = true)
    static class StateJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_STATE, Map.of("id", id), null); }
    }

    @Command(name = "history", description = "Get cron run history", mixinStandardHelpOptions = true)
    static class HistoryJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/cron/jobs/" + CliHttpSupport.url(id) + "/history", null); }
    }

    @Command(name = "dispatch-targets", description = "List cron dispatch targets", mixinStandardHelpOptions = true)
    static class DispatchTargets extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/cron/dispatch-targets", null); }
    }

    @Command(name = "create", description = "Create a cron job", mixinStandardHelpOptions = true)
    static class CreateJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        @Option(names = {"--cron", "--schedule"}, required = true) String cron;
        @Option(names = "--prompt", required = true) String prompt;
        @Option(names = "--agent", defaultValue = "default") String agent;
        @Option(names = "--disabled") boolean disabled;
        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("cron", cron);
            body.put("schedule", cron);
            body.put("prompt", prompt);
            body.put("agent", agent);
            body.put("enabled", !disabled);
            return execute(CliCommandSpecs.CRON_CREATE, Map.of(), body);
        }
    }

    @Command(name = "update", description = "Update a cron job", mixinStandardHelpOptions = true)
    static class UpdateJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Option(names = "--name") String name;
        @Option(names = {"--cron", "--schedule"}) String cron;
        @Option(names = "--prompt") String prompt;
        @Option(names = "--agent") String agent;
        @Option(names = "--enabled") Boolean enabled;
        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            if (name != null) body.put("name", name);
            if (cron != null) {
                body.put("cron", cron);
                body.put("schedule", cron);
            }
            if (prompt != null) body.put("prompt", prompt);
            if (agent != null) body.put("agent", agent);
            if (enabled != null) body.put("enabled", enabled);
            return execute(CliCommandSpecs.CRON_UPDATE, Map.of("id", id), body);
        }
    }

    @Command(name = "delete", description = "Delete a cron job", mixinStandardHelpOptions = true)
    static class DeleteJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_DELETE, Map.of("id", id), null); }
    }

    @Command(name = "pause", description = "Pause a cron job", mixinStandardHelpOptions = true)
    static class PauseJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_PAUSE, Map.of("id", id), null); }
    }

    @Command(name = "resume", description = "Resume a cron job", mixinStandardHelpOptions = true)
    static class ResumeJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_RESUME, Map.of("id", id), null); }
    }

    @Command(name = "run", description = "Run a cron job now", mixinStandardHelpOptions = true)
    static class RunJob extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.CRON_RUN, Map.of("id", id), null); }
    }
}
