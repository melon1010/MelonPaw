package com.melon.app.cli;

import com.melon.app.MelonApplication;
import com.melon.app.cli.context.CliOptionResolver;
import com.melon.app.cli.context.CliOptions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Start the HTTP service. Corresponds to Python cli/app_cmd.py.
 */
@Command(name = "app", description = "Start Melon HTTP server", mixinStandardHelpOptions = true)
public class AppCommand implements Callable<Integer> {

    @Spec
    CommandSpec commandSpec;

    @Option(names = "--reload", description = "Enable auto-reload. Accepted and ignored by this Java build.")
    boolean reload;

    @Option(names = "--log-level", defaultValue = "info", description = "Log level")
    String logLevel;

    @Option(names = "--hide-access-paths", split = ",", description = "Access-log path filters")
    String[] hideAccessPaths;

    @Option(names = "--workers", description = "Deprecated compatibility option. Always ignored.")
    Integer workers;

    @Override
    public Integer call() {
        CliOptions options = CliOptionResolver.from(commandSpec);
        if (workers != null) {
            System.err.println("Warning: --workers is deprecated and ignored; Melon uses one application process.");
        }
        if (reload) {
            System.err.println("Warning: --reload is accepted for compatibility and ignored by this Java build; restart melonpaw app to reload.");
        }
        new SpringApplicationBuilder(MelonApplication.class)
                .properties("server.address=" + options.getHost())
                .properties("server.port=" + options.getPort())
                .properties("logging.level.root=" + logLevel.toUpperCase(Locale.ROOT))
                .run();
        return 0;
    }
}
