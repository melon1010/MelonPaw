package com.melon.app.cli;

import picocli.CommandLine;
import com.melon.app.cli.context.CliOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.Arrays;
import java.util.concurrent.Callable;

/** Picocli root command group. */
@Command(
    name = "melonpaw",
    mixinStandardHelpOptions = true,
    subcommands = {
        AppCommand.class,
        InitCommand.class,
        AgentsCommand.class,
        ConfigCommand.class,
        WorkspaceCommand.class,
        ProjectCommand.class,
        GitCommand.class,
        McpCommand.class,
        BackupCommand.class,
        SecurityCommand.class,
        ToolsCommand.class,
        TokenUsageCommand.class,
        VoiceCommand.class,
        SkillsCommand.class,
        ModelsCommand.class,
        DoctorCommand.class,
        CronCommand.class,
        EnvCommand.class,
        ChatsCommand.class,
        ChannelsCommand.class,
        PluginCommand.class,
        CleanCommand.class,
        ShutdownCommand.class,
        TaskCommand.class,
        AcpCommand.class,
        DesktopCommand.class,
        AuthCommand.class,
        DaemonCommand.class,
        UpdateCommand.class,
        UninstallCommand.class,
        TuiCommand.class,
        AutoCommand.class
    },
    description = "MelonPaw - AI Agent powered by AgentScope 2.0"
)
public class MelonCli implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    CliOptions cliOptions;

    public CliOptions getCliOptions() {
        return cliOptions;
    }

    @Override
    public Integer call() {
        return TuiCommand.runDefault(spec);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MelonCli()).execute(expandBareProjectArgs(args));
        System.exit(exitCode);
    }

    static String[] expandBareProjectArgs(String[] args) {
        if (args.length == 0 || args[0].startsWith("-") || !TuiCommand.looksLikeProjectPath(args[0])) {
            return args;
        }
        String[] expanded = Arrays.copyOf(args, args.length + 1);
        System.arraycopy(expanded, 0, expanded, 1, args.length);
        expanded[0] = "tui";
        return expanded;
    }
}
