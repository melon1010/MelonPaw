package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "security", description = "Manage security settings", mixinStandardHelpOptions = true,
        subcommands = {SecurityCommand.ToolGuard.class, SecurityCommand.BuiltinRules.class,
                SecurityCommand.AuditEvents.class, SecurityCommand.FileGuard.class, SecurityCommand.SkillScanner.class,
                SecurityCommand.BlockedHistory.class, SecurityCommand.Whitelist.class, SecurityCommand.AllowNoAuthHosts.class})
public class SecurityCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/config/security/tool-guard", null); }

    @Command(name = "tool-guard", mixinStandardHelpOptions = true)
    static class ToolGuard extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            return fields == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/config/security/tool-guard", null, CliHttpSupport.agentHeader(agent))
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/config/security/tool-guard", CliHttpSupport.setBody(fields), CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "builtin-rules", mixinStandardHelpOptions = true)
    static class BuiltinRules extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/config/security/tool-guard/builtin-rules", null); }
    }

    @Command(name = "audit-events", mixinStandardHelpOptions = true)
    static class AuditEvents extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--session-id") String sessionId;
        @Option(names = "--tool-name") String toolName;
        @Option(names = "--decision") String decision;
        @Option(names = "--limit", defaultValue = "100") int limit;
        public Integer call() {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("session_id", sessionId);
            query.put("tool_name", toolName);
            query.put("decision", decision);
            query.put("limit", limit);
            return CliHttpSupport.request(commandSpec, "GET", "/api/config/security/audit-events"
                    + CliHttpSupport.query(query),
                    null, CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "file-guard", mixinStandardHelpOptions = true)
    static class FileGuard extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            return fields == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/config/security/file-guard", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/config/security/file-guard", CliHttpSupport.setBody(fields));
        }
    }

    @Command(name = "skill-scanner", mixinStandardHelpOptions = true)
    static class SkillScanner extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            return fields == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/config/security/skill-scanner", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/config/security/skill-scanner", CliHttpSupport.setBody(fields));
        }
    }

    @Command(name = "blocked-history", mixinStandardHelpOptions = true)
    static class BlockedHistory extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--clear") boolean clear;
        @Option(names = "--remove-index") Integer index;
        public Integer call() {
            if (clear) return CliHttpSupport.request(commandSpec, "DELETE", "/api/config/security/skill-scanner/blocked-history", null);
            if (index != null) return CliHttpSupport.request(commandSpec, "DELETE", "/api/config/security/skill-scanner/blocked-history/" + index, null);
            return CliHttpSupport.request(commandSpec, "GET", "/api/config/security/skill-scanner/blocked-history", null);
        }
    }

    @Command(name = "whitelist", mixinStandardHelpOptions = true)
    static class Whitelist extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--add") String add;
        @Option(names = "--hash", defaultValue = "") String hash;
        @Option(names = "--remove") String remove;
        public Integer call() {
            if (add != null) return CliHttpSupport.request(commandSpec, "POST", "/api/config/security/skill-scanner/whitelist", Map.of("skill_name", add, "content_hash", hash));
            if (remove != null) return CliHttpSupport.request(commandSpec, "DELETE", "/api/config/security/skill-scanner/whitelist/" + CliHttpSupport.url(remove), null);
            return CliHttpSupport.request(commandSpec, "GET", "/api/config/security/skill-scanner", null);
        }
    }

    @Command(name = "allow-no-auth-hosts", mixinStandardHelpOptions = true)
    static class AllowNoAuthHosts extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--allowed-host", split = ",") List<String> hosts;
        public Integer call() {
            return hosts == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/config/security/allow-no-auth-hosts", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/config/security/allow-no-auth-hosts", Map.of("hosts", hosts));
        }
    }
}
