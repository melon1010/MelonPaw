package com.melon.app.cli;

import com.melon.app.cli.spec.CliKeyValueParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "mcp", description = "Manage MCP clients and tools", mixinStandardHelpOptions = true,
        subcommands = {McpCommand.ListClients.class, McpCommand.Get.class, McpCommand.Create.class,
                McpCommand.Update.class, McpCommand.Toggle.class, McpCommand.Delete.class, McpCommand.Tools.class,
                McpCommand.SetTools.class, McpCommand.Policy.class, McpCommand.SetPolicy.class,
                McpCommand.OAuthStart.class, McpCommand.OAuthStatus.class, McpCommand.OAuthRevoke.class,
                McpCommand.Reload.class, McpCommand.CallTool.class})
public class McpCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/mcp", null); }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListClients extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/mcp", null); }
    }

    @Command(name = "get", mixinStandardHelpOptions = true)
    static class Get extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/mcp/" + CliHttpSupport.url(client), null); }
    }

    @Command(name = "create", mixinStandardHelpOptions = true)
    static class Create extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set", required = true) List<String> fields;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/mcp", CliHttpSupport.setBody(fields)); }
    }

    @Command(name = "update", mixinStandardHelpOptions = true)
    static class Update extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        @Option(names = "--set", required = true) List<String> fields;
        public Integer call() { return CliHttpSupport.request(commandSpec, "PUT", "/api/mcp/" + CliHttpSupport.url(client), CliHttpSupport.setBody(fields)); }
    }

    @Command(name = "toggle", mixinStandardHelpOptions = true)
    static class Toggle extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        public Integer call() { return CliHttpSupport.request(commandSpec, "PATCH", "/api/mcp/toggle/" + CliHttpSupport.url(client), Map.of()); }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true)
    static class Delete extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        public Integer call() { return CliHttpSupport.request(commandSpec, "DELETE", "/api/mcp/" + CliHttpSupport.url(client), null); }
    }

    @Command(name = "tools", mixinStandardHelpOptions = true)
    static class Tools extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client") String client;
        public Integer call() {
            String path = client == null ? "/api/mcp/tools" : "/api/mcp/tools/" + CliHttpSupport.url(client);
            return CliHttpSupport.request(commandSpec, "GET", path, null);
        }
    }

    @Command(name = "set-tools", mixinStandardHelpOptions = true)
    static class SetTools extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        @Option(names = "--tools", split = ",") List<String> tools;
        @Option(names = "--all") boolean all;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tools", all ? null : tools);
            return CliHttpSupport.request(commandSpec, "PUT", "/api/mcp/tools/" + CliHttpSupport.url(client),
                    body);
        }
    }

    @Command(name = "policy", mixinStandardHelpOptions = true)
    static class Policy extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/mcp/policy/" + CliHttpSupport.url(client), null); }
    }

    @Command(name = "set-policy", mixinStandardHelpOptions = true)
    static class SetPolicy extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        @Option(names = "--set", required = true) List<String> fields;
        public Integer call() { return CliHttpSupport.request(commandSpec, "PUT", "/api/mcp/policy/" + CliHttpSupport.url(client), CliHttpSupport.setBody(fields)); }
    }

    @Command(name = "oauth-start", mixinStandardHelpOptions = true)
    static class OAuthStart extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        @Option(names = "--set") List<String> fields;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/mcp/oauth/start/" + CliHttpSupport.url(client), CliHttpSupport.setBody(fields)); }
    }

    @Command(name = "oauth-status", mixinStandardHelpOptions = true)
    static class OAuthStatus extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/mcp/oauth/status/" + CliHttpSupport.url(client), null); }
    }

    @Command(name = "oauth-revoke", mixinStandardHelpOptions = true)
    static class OAuthRevoke extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        public Integer call() { return CliHttpSupport.request(commandSpec, "DELETE", "/api/mcp/oauth/" + CliHttpSupport.url(client), null); }
    }

    @Command(name = "reload", mixinStandardHelpOptions = true)
    static class Reload extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/mcp/reload", Map.of()); }
    }

    @Command(name = "call-tool", mixinStandardHelpOptions = true)
    static class CallTool extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--client", required = true) String client;
        @Option(names = "--tool", required = true) String tool;
        @Option(names = "--set") List<String> args;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tool", tool);
            body.put("args", CliKeyValueParser.parsePairs(args));
            return CliHttpSupport.request(commandSpec, "POST", "/api/mcp/servers/" + CliHttpSupport.url(client) + "/tools", body);
        }
    }
}
