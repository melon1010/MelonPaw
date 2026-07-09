package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import com.melon.app.cli.spec.CliKeyValueParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "agents", aliases = "agent", description = "Manage agents",
        mixinStandardHelpOptions = true,
        subcommands = {
                AgentsCommand.ListAgents.class,
                AgentsCommand.GetAgent.class,
                AgentsCommand.CreateAgent.class,
                AgentsCommand.UpdateAgent.class,
                AgentsCommand.DeleteAgent.class,
                AgentsCommand.EnableAgent.class,
                AgentsCommand.DisableAgent.class,
                AgentsCommand.OrderAgents.class,
                AgentsCommand.ChatAgent.class
        })
public class AgentsCommand extends AbstractHttpCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return execute(CliCommandSpecs.AGENTS_LIST);
    }

    @Command(name = "list", description = "List agents", mixinStandardHelpOptions = true)
    static class ListAgents extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.AGENTS_LIST); }
    }

    @Command(name = "get", description = "Get an agent", mixinStandardHelpOptions = true)
    static class GetAgent extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID") String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.AGENTS_GET, Map.of("id", id), null); }
    }

    @Command(name = "create", description = "Create an agent", mixinStandardHelpOptions = true)
    static class CreateAgent extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID") String id;
        @Option(names = "--name") String name;
        @Option(names = "--description") String description;
        @Option(names = "--workspace-dir") String workspaceDir;
        @Option(names = "--active-model") String activeModel;
        @Option(names = "--skill", split = ",") List<String> skills;
        @Option(names = "--disabled") boolean disabled;
        @Option(names = "--set", description = "Additional field as key=value") List<String> fields;

        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            body.put("id", id);
            if (name != null) body.put("name", name);
            if (description != null) body.put("description", description);
            if (workspaceDir != null) body.put("workspace_dir", workspaceDir);
            if (activeModel != null) body.put("active_model", activeModel);
            if (skills != null) body.put("skill_names", skills);
            body.put("enabled", !disabled);
            return execute(CliCommandSpecs.AGENTS_CREATE, Map.of(), body);
        }
    }

    @Command(name = "update", description = "Update an agent", mixinStandardHelpOptions = true)
    static class UpdateAgent extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID") String id;
        @Option(names = "--name") String name;
        @Option(names = "--description") String description;
        @Option(names = "--workspace-dir") String workspaceDir;
        @Option(names = "--active-model") String activeModel;
        @Option(names = "--skill", split = ",") List<String> skills;
        @Option(names = "--enabled") Boolean enabled;
        @Option(names = "--set", description = "Additional field as key=value") List<String> fields;

        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            if (name != null) body.put("name", name);
            if (description != null) body.put("description", description);
            if (workspaceDir != null) body.put("workspace_dir", workspaceDir);
            if (activeModel != null) body.put("active_model", activeModel);
            if (skills != null) body.put("skill_names", skills);
            if (enabled != null) body.put("enabled", enabled);
            return execute(CliCommandSpecs.AGENTS_UPDATE, Map.of("id", id), body);
        }
    }

    @Command(name = "delete", description = "Delete an agent", mixinStandardHelpOptions = true)
    static class DeleteAgent extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID") String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.AGENTS_DELETE, Map.of("id", id), null); }
    }

    @Command(name = "enable", description = "Enable an agent", mixinStandardHelpOptions = true)
    static class EnableAgent extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID") String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.AGENTS_TOGGLE, Map.of("id", id), Map.of("enabled", true)); }
    }

    @Command(name = "disable", description = "Disable an agent", mixinStandardHelpOptions = true)
    static class DisableAgent extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID") String id;
        @Override
        public Integer call() { return execute(CliCommandSpecs.AGENTS_TOGGLE, Map.of("id", id), Map.of("enabled", false)); }
    }

    @Command(name = "order", description = "Set agent order", mixinStandardHelpOptions = true)
    static class OrderAgents extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(arity = "1..*", paramLabel = "ID") List<String> ids;
        @Override
        public Integer call() { return execute(CliCommandSpecs.AGENTS_ORDER, Map.of(), Map.of("agent_ids", ids)); }
    }

    @Command(name = "chat", description = "Chat with an agent", mixinStandardHelpOptions = true)
    static class ChatAgent implements Callable<Integer> {
        @Spec CommandSpec commandSpec;
        @Parameters(index = "0", paramLabel = "AGENT") String agent;
        @Parameters(index = "1..*", paramLabel = "TEXT", arity = "0..*") List<String> text;
        @Option(names = {"--message", "--instruction"}) String message;
        @Option(names = "--user-id", defaultValue = "default") String userId;
        @Option(names = "--session-id", defaultValue = "default") String sessionId;
        @Option(names = "--task-timeout", defaultValue = "300") double taskTimeout;
        @Option(names = "--poll-interval", defaultValue = "1") double pollInterval;
        @Option(names = "--no-wait") boolean noWait;

        @Override
        public Integer call() {
            String input = message != null ? message : String.join(" ", text == null ? List.of() : text);
            return TaskCommand.submitAndPoll(commandSpec, agent, userId, sessionId, input, taskTimeout, pollInterval, noWait);
        }
    }
}
