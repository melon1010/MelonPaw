package com.melon.app.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "uninstall", description = "Show uninstall guidance", mixinStandardHelpOptions = true)
public class UninstallCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        System.out.println("Self-uninstall is not part of the open-source Java build.");
        System.out.println("Remove the installed wrapper scripts and application artifact from the location where you installed them.");
        return 0;
    }
}
