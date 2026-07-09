package com.melon.app.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "update", description = "Show upgrade guidance", mixinStandardHelpOptions = true)
public class UpdateCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        System.out.println("Self-update is not part of the open-source Java build.");
        System.out.println("Upgrade using your package manager, release artifact, or source checkout, then rebuild with: mvn -pl melon-app -am package");
        return 0;
    }
}
