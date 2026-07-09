package com.melon.app.cli;

import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.context.CliOptionResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Callable;

@Command(name = "desktop", description = "Open Melon in the system browser", mixinStandardHelpOptions = true)
public class DesktopCommand implements Callable<Integer> {
    @Spec CommandSpec commandSpec;

    @Override
    public Integer call() {
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        String url = context.baseUrl();
        System.out.println("Open " + url + " after starting the app with: melonpaw app");
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Could not open browser: " + e.getMessage());
            return 1;
        }
    }
}
