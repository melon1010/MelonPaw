package com.melon.app.cli.context;

import com.melon.app.cli.MelonCli;
import picocli.CommandLine.Model.CommandSpec;

public final class CliOptionResolver {

    private CliOptionResolver() {
    }

    public static CliOptions from(CommandSpec commandSpec) {
        CommandSpec current = commandSpec;
        while (current != null) {
            Object userObject = current.userObject();
            if (userObject instanceof MelonCli melonCli) {
                return melonCli.getCliOptions();
            }
            current = current.parent();
        }
        return new CliOptions();
    }
}
