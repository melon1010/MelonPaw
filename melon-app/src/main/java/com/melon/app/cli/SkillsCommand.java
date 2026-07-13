package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import com.melon.app.cli.spec.CliKeyValueParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "skills", description = "Manage skills", mixinStandardHelpOptions = true,
        subcommands = {
                SkillsCommand.ListSkills.class,
                SkillsCommand.RefreshSkills.class,
                SkillsCommand.Workspaces.class,
                SkillsCommand.Pool.class,
                SkillsCommand.HubSearch.class,
                SkillsCommand.InfoSkill.class,
                SkillsCommand.CreateSkill.class,
                SkillsCommand.SaveSkill.class,
                SkillsCommand.EnableSkill.class,
                SkillsCommand.DisableSkill.class,
                SkillsCommand.BatchEnable.class,
                SkillsCommand.BatchDisable.class,
                SkillsCommand.BatchDelete.class,
                SkillsCommand.ConfigSkills.class,
                SkillsCommand.DeleteConfig.class,
                SkillsCommand.SetTags.class,
                SkillsCommand.SetChannels.class,
                SkillsCommand.InstallSkill.class,
                SkillsCommand.InstallStatus.class,
                SkillsCommand.InstallCancel.class,
                SkillsCommand.ImportBuiltins.class,
                SkillsCommand.BuiltinSources.class,
                SkillsCommand.BuiltinNotice.class,
                SkillsCommand.PoolUpload.class,
                SkillsCommand.PoolDownload.class,
                SkillsCommand.UninstallSkill.class,
                SkillsCommand.TestSkill.class
        })
public class SkillsCommand extends AbstractHttpCommand implements Callable<Integer> {

    @Override
    public Integer call() { return execute(CliCommandSpecs.SKILLS_LIST); }

    @Command(name = "list", description = "List skills", mixinStandardHelpOptions = true)
    static class ListSkills extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_LIST); }
    }

    @Command(name = "refresh", description = "Refresh skills", mixinStandardHelpOptions = true)
    static class RefreshSkills extends AbstractHttpCommand implements Callable<Integer> {
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_REFRESH); }
    }

    @Command(name = "workspaces", description = "List skill workspaces", mixinStandardHelpOptions = true)
    static class Workspaces extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/skills/workspaces", null); }
    }

    @Command(name = "pool", description = "List skill pool", mixinStandardHelpOptions = true)
    static class Pool extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--refresh") boolean refresh;
        public Integer call() { return CliHttpSupport.request(commandSpec, refresh ? "POST" : "GET", "/api/skills/pool" + (refresh ? "/refresh" : ""), refresh ? Map.of() : null); }
    }

    @Command(name = "hub-search", description = "Search skill hub", mixinStandardHelpOptions = true)
    static class HubSearch extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--query") String query;
        @Option(names = "--limit", defaultValue = "20") int limit;
        public Integer call() {
            java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("q", query);
            params.put("limit", limit);
            return CliHttpSupport.request(commandSpec, "GET", "/api/skills/hub/search" + CliHttpSupport.query(params), null);
        }
    }

    @Command(name = "info", description = "Get skill content", mixinStandardHelpOptions = true)
    static class InfoSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_GET, Map.of("name", name), null); }
    }

    @Command(name = "create", description = "Create a skill", mixinStandardHelpOptions = true)
    static class CreateSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Option(names = "--content", defaultValue = "") String content;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_CREATE, Map.of(), Map.of("name", name, "content", content)); }
    }

    @Command(name = "save", description = "Save a skill", mixinStandardHelpOptions = true)
    static class SaveSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Option(names = "--source-name") String sourceName;
        @Option(names = "--content", defaultValue = "") String content;
        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("content", content);
            if (sourceName != null) body.put("source_name", sourceName);
            return execute(CliCommandSpecs.SKILLS_SAVE, Map.of(), body);
        }
    }

    @Command(name = "enable", description = "Enable a skill", mixinStandardHelpOptions = true)
    static class EnableSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_ENABLE, Map.of("name", name), null); }
    }

    @Command(name = "disable", description = "Disable a skill", mixinStandardHelpOptions = true)
    static class DisableSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_DISABLE, Map.of("name", name), null); }
    }

    @Command(name = "batch-enable", mixinStandardHelpOptions = true)
    static class BatchEnable extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--name", split = ",", required = true) java.util.List<String> names;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/skills/batch-enable", names, agent == null ? Map.of() : Map.of("X-Agent-Id", agent)); }
    }

    @Command(name = "batch-disable", mixinStandardHelpOptions = true)
    static class BatchDisable extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--name", split = ",", required = true) java.util.List<String> names;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/skills/batch-disable", names, agent == null ? Map.of() : Map.of("X-Agent-Id", agent)); }
    }

    @Command(name = "batch-delete", mixinStandardHelpOptions = true)
    static class BatchDelete extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--pool") boolean pool;
        @Option(names = "--name", split = ",", required = true) java.util.List<String> names;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "POST", pool ? "/api/skills/pool/batch-delete" : "/api/skills/batch-delete",
                    names, agent == null ? Map.of() : Map.of("X-Agent-Id", agent));
        }
    }

    @Command(name = "config", description = "Get or update skill config", mixinStandardHelpOptions = true)
    static class ConfigSkills extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Option(names = "--set", description = "Config key=value") java.util.List<String> fields;
        @Override
        public Integer call() {
            if (fields == null || fields.isEmpty()) {
                return execute(CliCommandSpecs.SKILLS_CONFIG_GET, Map.of("name", name), null);
            }
            return execute(CliCommandSpecs.SKILLS_CONFIG_SET, Map.of("name", name), CliKeyValueParser.parsePairs(fields));
        }
    }

    @Command(name = "delete-config", description = "Delete skill config", mixinStandardHelpOptions = true)
    static class DeleteConfig extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_CONFIG_DELETE, Map.of("name", name), null); }
    }

    @Command(name = "tags", description = "Set skill tags", mixinStandardHelpOptions = true)
    static class SetTags extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Option(names = "--tags", required = true, description = "Comma-separated tags") String tags;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_TAGS_SET, Map.of("name", name), CliKeyValueParser.csv(tags)); }
    }

    @Command(name = "channels", description = "Set skill channels", mixinStandardHelpOptions = true)
    static class SetChannels extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Option(names = "--channels", required = true, description = "Comma-separated channels") String channels;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_CHANNELS_SET, Map.of("name", name), CliKeyValueParser.csv(channels)); }
    }

    @Command(name = "install", description = "Install a skill from builtin, pool, or local hub source", mixinStandardHelpOptions = true)
    static class InstallSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Option(names = "--agent") String agent;
        @Option(names = "--source", description = "source_url/bundle_url; defaults to NAME") String source;
        @Option(names = "--overwrite") boolean overwrite;
        @Option(names = "--disable") boolean disable;
        @Option(names = "--set", description = "Install field key=value") java.util.List<String> fields;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliKeyValueParser.parsePairs(fields));
            body.putIfAbsent("skill_name", name);
            body.putIfAbsent("name", name);
            body.putIfAbsent("source_url", source != null ? source : name);
            body.put("overwrite", overwrite);
            body.put("enable", !disable);
            return execute(CliCommandSpecs.SKILLS_INSTALL_START, Map.of(), body, agent == null || agent.isBlank() ? Map.of() : Map.of("X-Agent-Id", agent));
        }
    }

    @Command(name = "install-status", description = "Show skill install task status", mixinStandardHelpOptions = true)
    static class InstallStatus extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "TASK_ID") String taskId;
        public Integer call() { return execute(CliCommandSpecs.SKILLS_INSTALL_STATUS, Map.of("taskId", taskId), null); }
    }

    @Command(name = "install-cancel", description = "Cancel skill install task", mixinStandardHelpOptions = true)
    static class InstallCancel extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "TASK_ID") String taskId;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/skills/hub/install/cancel/" + CliHttpSupport.url(taskId), Map.of()); }
    }

    @Command(name = "import-builtin", description = "Import built-in skills into the pool", mixinStandardHelpOptions = true)
    static class ImportBuiltins extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(arity = "0..*", paramLabel = "NAME") java.util.List<String> names;
        @Option(names = "--overwrite") boolean overwrite;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            if (names != null && !names.isEmpty()) body.put("skill_names", names);
            body.put("overwrite_conflicts", overwrite);
            return execute(CliCommandSpecs.SKILLS_IMPORT_BUILTIN, Map.of(), body);
        }
    }

    @Command(name = "builtin-sources", mixinStandardHelpOptions = true)
    static class BuiltinSources extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/skills/pool/builtin-sources", null); }
    }

    @Command(name = "builtin-notice", mixinStandardHelpOptions = true)
    static class BuiltinNotice extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/skills/pool/builtin-notice", null); }
    }

    @Command(name = "pool-upload", description = "Upload workspace skill to pool", mixinStandardHelpOptions = true)
    static class PoolUpload extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--workspace-id", defaultValue = "default") String workspaceId;
        @Option(names = "--skill", required = true) String skill;
        @Option(names = "--overwrite") boolean overwrite;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "POST", "/api/skills/pool/upload",
                    Map.of("workspace_id", workspaceId, "skill_name", skill, "overwrite", overwrite));
        }
    }

    @Command(name = "pool-download", description = "Download pool skill to workspace", mixinStandardHelpOptions = true)
    static class PoolDownload extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--workspace-id", split = ",") java.util.List<String> workspaceIds;
        @Option(names = "--skill", required = true) String skill;
        @Option(names = "--overwrite") boolean overwrite;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("skill_name", skill);
            body.put("overwrite", overwrite);
            if (workspaceIds != null) body.put("workspace_ids", workspaceIds);
            return CliHttpSupport.request(commandSpec, "POST", "/api/skills/pool/download", body);
        }
    }

    @Command(name = "uninstall", description = "Delete a skill", mixinStandardHelpOptions = true)
    static class UninstallSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        @Override
        public Integer call() { return execute(CliCommandSpecs.SKILLS_DELETE, Map.of("name", name), null); }
    }

    @Command(name = "test", description = "Check that a skill is visible to the backend", mixinStandardHelpOptions = true)
    static class TestSkill extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "NAME") String name;
        public Integer call() { return execute(CliCommandSpecs.SKILLS_GET, Map.of("name", name), null); }
    }
}
