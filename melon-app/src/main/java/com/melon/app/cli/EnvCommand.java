package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "env",
    aliases = "envs",
    description = "Manage environment variables",
    mixinStandardHelpOptions = true,
    subcommands = { EnvCommand.ListEnvs.class, EnvCommand.GetEnv.class, EnvCommand.SetEnv.class, EnvCommand.DeleteEnv.class }
)
public class EnvCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: melonpaw env <subcommand> [options]");
        System.out.println("Subcommands: list, get, set, delete");
    }

    @Command(name = "list", description = "List all environment variables", mixinStandardHelpOptions = true)
    static class ListEnvs extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.ENV_LIST); }
    }

    @Command(name = "get", description = "Get an environment variable", mixinStandardHelpOptions = true)
    static class GetEnv extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "KEY") String key;
        @Override
        public Integer call() { return execute(CliCommandSpecs.ENV_GET, Map.of("key", key), null); }
    }

    @Command(name = "set", description = "Set an environment variable", mixinStandardHelpOptions = true)
    static class SetEnv extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "KEY") String key;
        @Parameters(index = "1", paramLabel = "VALUE") String value;
        @Override
        public Integer call() { return execute(CliCommandSpecs.ENV_SET, Map.of("key", key), Map.of("value", value)); }
    }

    @Command(name = "delete", description = "Delete an environment variable", mixinStandardHelpOptions = true)
    static class DeleteEnv extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "KEY") String key;
        @Override
        public Integer call() { return execute(CliCommandSpecs.ENV_DELETE, Map.of("key", key), null); }
    }
}
