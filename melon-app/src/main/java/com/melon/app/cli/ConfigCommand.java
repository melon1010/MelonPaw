package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpec;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "config", description = "Manage configuration", mixinStandardHelpOptions = true)
public class ConfigCommand extends AbstractHttpCommand implements Callable<Integer> {

    private static final CliCommandSpec CONFIG_GET = CliCommandSpec.builder("config.get")
            .get("/api/workspace/running-config")
            .description("Get runtime configuration")
            .build();

    @Override
    public Integer call() {
        return execute(CONFIG_GET);
    }
}
