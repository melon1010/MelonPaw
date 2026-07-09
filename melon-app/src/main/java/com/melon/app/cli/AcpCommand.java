package com.melon.app.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "acp", description = "Explain ACP runtime availability", mixinStandardHelpOptions = true)
public class AcpCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        System.err.println("ACP is not available in this Java build because no Java ACP runtime is registered.");
        System.err.println("Add a Java ACP server/runtime first, then wire this command to it.");
        return 1;
    }
}
