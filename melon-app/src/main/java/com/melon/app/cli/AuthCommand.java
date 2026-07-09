package com.melon.app.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "auth", description = "Manage web authentication", mixinStandardHelpOptions = true,
        subcommands = AuthCommand.ResetPassword.class)
public class AuthCommand implements Runnable {
    public void run() { System.out.println("Usage: melonpaw auth <subcommand> [options]"); }

    @Command(name = "reset-password", mixinStandardHelpOptions = true)
    static class ResetPassword implements Callable<Integer> {
        public Integer call() {
            System.err.println("Password reset is environment-based in this Java build.");
            System.err.println("Set MELON_PASSWORD or MELON_PASSWORD_HASH, then restart melonpaw app.");
            return 1;
        }
    }
}
