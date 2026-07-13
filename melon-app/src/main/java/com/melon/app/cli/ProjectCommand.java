package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "project", description = "Manage coding projects", mixinStandardHelpOptions = true,
        subcommands = {ProjectCommand.Get.class, ProjectCommand.Set.class, ProjectCommand.ListProjects.class,
                ProjectCommand.Create.class, ProjectCommand.ImportLocal.class, ProjectCommand.UploadZip.class,
                ProjectCommand.BrowseDirs.class, ProjectCommand.Clone.class})
public class ProjectCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/coding-project", null); }

    @Command(name = "get", mixinStandardHelpOptions = true)
    static class Get extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/coding-project", null); }
    }

    @Command(name = "set", mixinStandardHelpOptions = true)
    static class Set extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path") String path;
        @Option(names = "--default") boolean useDefault;
        public Integer call() {
            if (!useDefault && (path == null || path.isBlank())) {
                System.err.println("Use --path PATH or --default.");
                return 1;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", useDefault ? null : path);
            return CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/coding-project", body);
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListProjects extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/coding-project/list", null); }
    }

    @Command(name = "create", mixinStandardHelpOptions = true)
    static class Create extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) String name;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/workspace/coding-project/create", Map.of("name", name)); }
    }

    @Command(name = "import-local", mixinStandardHelpOptions = true)
    static class ImportLocal extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path", required = true) String path;
        @Option(names = "--name") String name;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", path);
            if (name != null) body.put("name", name);
            return CliHttpSupport.request(commandSpec, "POST", "/api/workspace/coding-project/import-local", body);
        }
    }

    @Command(name = "upload-zip", mixinStandardHelpOptions = true)
    static class UploadZip extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--file", required = true) Path file;
        @Option(names = "--name", required = true) String name;
        public Integer call() {
            return CliHttpSupport.multipart(commandSpec,
                    "/api/workspace/coding-project/upload-zip?name=" + CliHttpSupport.url(name),
                    file, "file", Map.of(), Map.of());
        }
    }

    @Command(name = "browse-dirs", mixinStandardHelpOptions = true)
    static class BrowseDirs extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--path", defaultValue = "~") String path;
        @Option(names = "--show-hidden") boolean showHidden;
        public Integer call() {
            return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/coding-project/browse-dirs"
                    + CliHttpSupport.query(Map.of("path", path, "show_hidden", showHidden ? "true" : "")), null);
        }
    }

    @Command(name = "clone", mixinStandardHelpOptions = true)
    static class Clone extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--url", required = true) String url;
        @Option(names = "--name") String name;
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("url", url);
            if (name != null) body.put("name", name);
            return CliHttpSupport.sse(commandSpec, "/api/workspace/coding-project/clone", body, Map.of());
        }
    }
}
