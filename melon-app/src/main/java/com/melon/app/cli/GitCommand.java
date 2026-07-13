package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "git", description = "Manage workspace git state", mixinStandardHelpOptions = true,
        subcommands = {GitCommand.Status.class, GitCommand.Branches.class, GitCommand.Checkout.class,
                GitCommand.Diff.class, GitCommand.Stage.class, GitCommand.Unstage.class, GitCommand.Commit.class,
                GitCommand.Log.class, GitCommand.Discard.class, GitCommand.CommitDiff.class, GitCommand.Revert.class})
public class GitCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    @Option(names = "--agent") String agent;
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/git/status", null, CliHttpSupport.agentHeader(agent)); }

    @Command(name = "status", mixinStandardHelpOptions = true)
    static class Status extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/git/status", null, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "branches", mixinStandardHelpOptions = true)
    static class Branches extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/git/branches", null, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "checkout", mixinStandardHelpOptions = true)
    static class Checkout extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--branch", required = true) String branch;
        @Option(names = "--create") boolean create;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/workspace/git/checkout", Map.of("branch", branch, "create", create), CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "diff", mixinStandardHelpOptions = true)
    static class Diff extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path") String path;
        @Option(names = "--staged") boolean staged;
        @Option(names = "--untracked") boolean untracked;
        @Option(names = "--agent") String agent;
        public Integer call() {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("path", path);
            query.put("staged", staged);
            query.put("untracked", untracked);
            return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/git/diff"
                    + CliHttpSupport.query(query), null, CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "stage", mixinStandardHelpOptions = true)
    static class Stage extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path", split = ",") List<String> paths;
        @Option(names = "--agent") String agent;
        public Integer call() { return pathsCommand(commandSpec, "/api/workspace/git/stage", paths, agent); }
    }

    @Command(name = "unstage", mixinStandardHelpOptions = true)
    static class Unstage extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path", split = ",") List<String> paths;
        @Option(names = "--agent") String agent;
        public Integer call() { return pathsCommand(commandSpec, "/api/workspace/git/unstage", paths, agent); }
    }

    @Command(name = "commit", mixinStandardHelpOptions = true)
    static class Commit extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = {"-m", "--message"}, required = true) String message;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/workspace/git/commit", Map.of("message", message), CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "log", mixinStandardHelpOptions = true)
    static class Log extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--limit", defaultValue = "20") int limit;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/git/log" + CliHttpSupport.query(Map.of("limit", limit)), null, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "discard", mixinStandardHelpOptions = true)
    static class Discard extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path", split = ",") List<String> paths;
        @Option(names = "--agent") String agent;
        @Option(names = "--yes", required = true) boolean yes;
        public Integer call() { return pathsCommand(commandSpec, "/api/workspace/git/discard", paths, agent); }
    }

    @Command(name = "commit-diff", mixinStandardHelpOptions = true)
    static class CommitDiff extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--hash", required = true) String hash;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/git/commit-diff" + CliHttpSupport.query(Map.of("commit_hash", hash)), null, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "revert", mixinStandardHelpOptions = true)
    static class Revert extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--hash", required = true) String hash;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/workspace/git/revert", Map.of("commit_hash", hash), CliHttpSupport.agentHeader(agent)); }
    }

    private static int pathsCommand(picocli.CommandLine.Model.CommandSpec spec, String path, List<String> paths, String agent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paths", paths == null ? List.of() : paths);
        return CliHttpSupport.request(spec, "POST", path, body, CliHttpSupport.agentHeader(agent));
    }
}
