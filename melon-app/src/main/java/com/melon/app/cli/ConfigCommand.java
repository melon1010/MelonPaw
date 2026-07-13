package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpec;
import com.melon.app.cli.CliHttpSupport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "config", description = "Manage configuration", mixinStandardHelpOptions = true,
        subcommands = ConfigCommand.SetConfig.class)
public class ConfigCommand extends AbstractHttpCommand implements Callable<Integer> {

    private static final CliCommandSpec CONFIG_GET = CliCommandSpec.builder("config.get")
            .get("/api/workspace/running-config")
            .description("Get runtime configuration")
            .build();

    @Override
    public Integer call() {
        return execute(CONFIG_GET);
    }

    @Command(name = "set", mixinStandardHelpOptions = true)
    static class SetConfig extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--set", required = true) List<String> fields;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/running-config",
                    CliHttpSupport.setBody(fields), CliHttpSupport.agentHeader(agent));
        }
    }
}
