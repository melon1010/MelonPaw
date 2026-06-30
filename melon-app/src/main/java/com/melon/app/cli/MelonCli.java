/**
 * @author melon
 */
package com.melon.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Picocli 根命令组. 对应 Python cli/main.py 的 LazyGroup.
 */
@Command(
    name = "melon",
    mixinStandardHelpOptions = true,
    subcommands = {
        AppCommand.class,
        InitCommand.class,
        AgentsCommand.class,
        ConfigCommand.class,
        SkillsCommand.class,
        ModelsCommand.class,
        DoctorCommand.class,
        CronCommand.class,
        EnvCommand.class
    },
    description = "Melon - AI Agent powered by AgentScope 2.0"
)
public class MelonCli implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: melon <command> [options]");
        System.out.println("Commands: app, init, agents, config, skills, models, doctor, cron, env");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MelonCli()).execute(args);
        System.exit(exitCode);
    }
}
