package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "tools", description = "Manage built-in tools", mixinStandardHelpOptions = true,
        subcommands = {ToolsCommand.ListTools.class, ToolsCommand.Toggle.class, ToolsCommand.Async.class,
                ToolsCommand.Config.class, ToolsCommand.GetConfig.class})
public class ToolsCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    @Option(names = "--agent") String agent;
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/tools", null, CliHttpSupport.agentHeader(agent)); }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListTools extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/tools", null, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "toggle", mixinStandardHelpOptions = true)
    static class Toggle extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        @Option(names = "--enabled") Boolean enabled;
        @Option(names = "--agent") String agent;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "PATCH", "/api/tools/" + CliHttpSupport.url(name) + "/toggle",
                    enabled == null ? Map.of() : Map.of("enabled", enabled), CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "async", mixinStandardHelpOptions = true)
    static class Async extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        @Option(names = "--enabled", required = true) boolean enabled;
        @Option(names = "--agent") String agent;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "PATCH", "/api/tools/" + CliHttpSupport.url(name) + "/async-execution",
                    Map.of("async_execution", enabled), CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "config", mixinStandardHelpOptions = true)
    static class Config extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        @Option(names = "--set", required = true) java.util.List<String> fields;
        @Option(names = "--agent") String agent;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "POST", "/api/tools/" + CliHttpSupport.url(name) + "/config",
                    Map.of("config", CliHttpSupport.setBody(fields)), CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "get-config", mixinStandardHelpOptions = true)
    static class GetConfig extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/tools/" + CliHttpSupport.url(name) + "/config", null, CliHttpSupport.agentHeader(agent)); }
    }
}
