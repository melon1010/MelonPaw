package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import com.melon.app.cli.spec.CliKeyValueParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "channels", aliases = "channel", description = "Manage channel configuration",
        mixinStandardHelpOptions = true,
        subcommands = {ChannelsCommand.ListChannels.class, ChannelsCommand.Types.class, ChannelsCommand.Meta.class,
                ChannelsCommand.GetChannel.class, ChannelsCommand.InstallChannel.class, ChannelsCommand.AddChannel.class,
                ChannelsCommand.RemoveChannel.class, ChannelsCommand.ConfigChannel.class, ChannelsCommand.StartChannel.class,
                ChannelsCommand.StopChannel.class, ChannelsCommand.RestartChannel.class, ChannelsCommand.HealthChannel.class,
                ChannelsCommand.Qrcode.class, ChannelsCommand.QrcodeStatus.class, ChannelsCommand.AccessControl.class,
                ChannelsCommand.SendChannel.class})
public class ChannelsCommand extends AbstractHttpCommand implements Callable<Integer> {
    @Option(names = "--agent", description = "Agent id for channel-scoped config") String agent;

    @Override
    public Integer call() { return execute(CliCommandSpecs.CHANNELS_LIST, Map.of(), null, headers(agent)); }

    static Map<String, String> headers(String agent) {
        return agent == null || agent.isBlank() ? Map.of() : Map.of("X-Agent-Id", agent);
    }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListChannels extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_LIST, Map.of(), null, headers(agent)); }
    }

    @Command(name = "types", mixinStandardHelpOptions = true)
    static class Types extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_TYPES); }
    }

    @Command(name = "meta", mixinStandardHelpOptions = true)
    static class Meta extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_TYPES_META); }
    }

    @Command(name = "get", mixinStandardHelpOptions = true)
    static class GetChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_GET, Map.of("channel", channel), null, headers(agent)); }
    }

    @Command(name = "install", description = "Show built-in channel types", mixinStandardHelpOptions = true)
    static class InstallChannel extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_TYPES_META); }
    }

    @Command(name = "add", description = "Enable or create channel config", mixinStandardHelpOptions = true)
    static class AddChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        @Option(names = "--set", description = "Config key=value") List<String> fields;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            body.putIfAbsent("enabled", true);
            return execute(CliCommandSpecs.CHANNELS_CONFIG, Map.of("channel", channel), body, headers(agent));
        }
    }

    @Command(name = "remove", description = "Disable channel config", mixinStandardHelpOptions = true)
    static class RemoveChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_CONFIG, Map.of("channel", channel), Map.of("enabled", false), headers(agent)); }
    }

    @Command(name = "config", description = "Get or update channel config", mixinStandardHelpOptions = true)
    static class ConfigChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        @Option(names = "--set", description = "Config key=value") List<String> fields;
        public Integer call() {
            if (fields == null || fields.isEmpty()) {
                return execute(CliCommandSpecs.CHANNELS_GET, Map.of("channel", channel), null, headers(agent));
            }
            return execute(CliCommandSpecs.CHANNELS_CONFIG, Map.of("channel", channel), CliKeyValueParser.parsePairs(fields), headers(agent));
        }
    }

    @Command(name = "start", mixinStandardHelpOptions = true)
    static class StartChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_START, Map.of("channel", channel), null, headers(agent)); }
    }

    @Command(name = "stop", mixinStandardHelpOptions = true)
    static class StopChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_STOP, Map.of("channel", channel), null, headers(agent)); }
    }

    @Command(name = "restart", mixinStandardHelpOptions = true)
    static class RestartChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_RESTART, Map.of("channel", channel), null, headers(agent)); }
    }

    @Command(name = "health", mixinStandardHelpOptions = true)
    static class HealthChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL", arity = "0..1") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() {
            return channel == null
                    ? execute(CliCommandSpecs.CHANNELS_HEALTH_ALL, Map.of(), null, headers(agent))
                    : execute(CliCommandSpecs.CHANNELS_HEALTH, Map.of("channel", channel), null, headers(agent));
        }
    }

    @Command(name = "qrcode", mixinStandardHelpOptions = true)
    static class Qrcode extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_QRCODE, Map.of("channel", channel), null, headers(agent)); }
    }

    @Command(name = "qrcode-status", mixinStandardHelpOptions = true)
    static class QrcodeStatus extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() { return execute(CliCommandSpecs.CHANNELS_QRCODE_STATUS, Map.of("channel", channel), null, headers(agent)); }
    }

    @Command(name = "access-control", mixinStandardHelpOptions = true,
            subcommands = {AccessControlWhitelist.class, AccessControlBlacklist.class,
                    AccessControlPending.class, AccessControlRemark.class, AccessControlUsername.class})
    static class AccessControl extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL", arity = "0..1") String channel;
        @Option(names = "--agent") String agent;
        public Integer call() {
            return channel == null
                    ? execute(CliCommandSpecs.CHANNELS_ACCESS_CONTROL, Map.of(), null, headers(agent))
                    : execute(CliCommandSpecs.CHANNELS_ACCESS_CONTROL_CHANNEL, Map.of("channel", channel), null, headers(agent));
        }
    }

    @Command(name = "whitelist", mixinStandardHelpOptions = true)
    static class AccessControlWhitelist extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--add") String add;
        @Option(names = "--remove") String remove;
        @Option(names = "--agent") String agent;
        public Integer call() {
            if (add != null) return CliHttpSupport.request(commandSpec, "POST", "/api/access-control/" + CliHttpSupport.url(channel) + "/whitelist", Map.of("user_id", add), headers(agent));
            if (remove != null) return CliHttpSupport.request(commandSpec, "DELETE", "/api/access-control/" + CliHttpSupport.url(channel) + "/whitelist/" + CliHttpSupport.url(remove), null, headers(agent));
            return execute(CliCommandSpecs.CHANNELS_ACCESS_CONTROL_CHANNEL, Map.of("channel", channel), null, headers(agent));
        }
    }

    @Command(name = "blacklist", mixinStandardHelpOptions = true)
    static class AccessControlBlacklist extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--add") String add;
        @Option(names = "--remove") String remove;
        @Option(names = "--agent") String agent;
        public Integer call() {
            if (add != null) return CliHttpSupport.request(commandSpec, "POST", "/api/access-control/" + CliHttpSupport.url(channel) + "/blacklist", Map.of("user_id", add), headers(agent));
            if (remove != null) return CliHttpSupport.request(commandSpec, "DELETE", "/api/access-control/" + CliHttpSupport.url(channel) + "/blacklist/" + CliHttpSupport.url(remove), null, headers(agent));
            return execute(CliCommandSpecs.CHANNELS_ACCESS_CONTROL_CHANNEL, Map.of("channel", channel), null, headers(agent));
        }
    }

    @Command(name = "pending", mixinStandardHelpOptions = true)
    static class AccessControlPending extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL", arity = "0..1") String channel;
        @Option(names = "--user-id") String userId;
        @Option(names = "--approve") boolean approve;
        @Option(names = "--deny") boolean deny;
        @Option(names = "--agent") String agent;
        public Integer call() {
            if (approve || deny) {
                if (channel == null || userId == null) {
                    System.err.println("--channel and --user-id are required for approve/deny.");
                    return 1;
                }
                String action = approve ? "approve" : "deny";
                return CliHttpSupport.request(commandSpec, "POST", "/api/access-control/" + CliHttpSupport.url(channel)
                        + "/pending/" + CliHttpSupport.url(userId) + "/" + action, Map.of(), headers(agent));
            }
            return CliHttpSupport.request(commandSpec, "GET", "/api/access-control/pending/all", null, headers(agent));
        }
    }

    @Command(name = "remark", mixinStandardHelpOptions = true)
    static class AccessControlRemark extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--user-id", required = true) String userId;
        @Option(names = "--remark", required = true) String remark;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/access-control/remark", Map.of("user_id", userId, "remark", remark), headers(agent)); }
    }

    @Command(name = "username", mixinStandardHelpOptions = true)
    static class AccessControlUsername extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--user-id", required = true) String userId;
        @Option(names = "--username", required = true) String username;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/access-control/username", Map.of("user_id", userId, "username", username), headers(agent)); }
    }

    @Command(name = "send", description = "Send webhook payload to a channel", mixinStandardHelpOptions = true)
    static class SendChannel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "CHANNEL") String channel;
        @Option(names = "--agent") String agent;
        @Option(names = "--text") String text;
        @Option(names = "--user-id") String userId;
        @Option(names = "--session-id") String sessionId;
        @Option(names = "--set", description = "Payload key=value") List<String> fields;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            if (text != null) body.put("text", text);
            if (userId != null) body.put("user_id", userId);
            if (sessionId != null) body.put("session_id", sessionId);
            return execute(CliCommandSpecs.CHANNELS_WEBHOOK, Map.of("channel", channel), body, headers(agent));
        }
    }
}
