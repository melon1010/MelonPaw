package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "backup", description = "Manage backups", mixinStandardHelpOptions = true,
        subcommands = {BackupCommand.ListBackups.class, BackupCommand.Get.class, BackupCommand.Create.class,
                BackupCommand.Restore.class, BackupCommand.Delete.class, BackupCommand.Export.class, BackupCommand.Import.class})
public class BackupCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/backups", null); }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListBackups extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/backups", null); }
    }

    @Command(name = "get", mixinStandardHelpOptions = true)
    static class Get extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/backups/" + CliHttpSupport.url(id), null); }
    }

    @Command(name = "create", mixinStandardHelpOptions = true)
    static class Create extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name") String name;
        @Option(names = "--description") String description;
        @Option(names = "--agent", split = ",") List<String> agents;
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>(CliHttpSupport.setBody(fields));
            if (name != null) body.put("name", name);
            if (description != null) body.put("description", description);
            if (agents != null) body.put("agents", agents);
            return CliHttpSupport.sse(commandSpec, "/api/backups/stream", body, Map.of());
        }
    }

    @Command(name = "restore", mixinStandardHelpOptions = true)
    static class Restore extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Option(names = "--yes", required = true) boolean yes;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/backups/" + CliHttpSupport.url(id) + "/restore", Map.of()); }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true)
    static class Delete extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", split = ",", required = true) List<String> ids;
        @Option(names = "--yes", required = true) boolean yes;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/backups/delete", Map.of("ids", ids)); }
    }

    @Command(name = "export", mixinStandardHelpOptions = true)
    static class Export extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) String id;
        @Option(names = "--save-to") Path output;
        public Integer call() { return CliHttpSupport.download(commandSpec, "/api/backups/" + CliHttpSupport.url(id) + "/export", output, Map.of()); }
    }

    @Command(name = "import", mixinStandardHelpOptions = true)
    static class Import extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--file", required = true) Path file;
        @Option(names = "--trust-mode") String trustMode;
        public Integer call() {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (trustMode != null) fields.put("trust_mode", trustMode);
            return CliHttpSupport.multipart(commandSpec, "/api/backups/import", file, "file", fields, Map.of());
        }
    }
}
