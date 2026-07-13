package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "workspace", description = "Manage workspace files and settings", mixinStandardHelpOptions = true,
        subcommands = {
                WorkspaceCommand.Info.class, WorkspaceCommand.Init.class, WorkspaceCommand.Config.class,
                WorkspaceCommand.Language.class, WorkspaceCommand.Files.class, WorkspaceCommand.Memory.class,
                WorkspaceCommand.CodeFiles.class, WorkspaceCommand.Download.class, WorkspaceCommand.Upload.class,
                WorkspaceCommand.SystemPromptFiles.class
        })
public class WorkspaceCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    @Option(names = "--agent") String agent;

    public Integer call() {
        return CliHttpSupport.request(commandSpec, "GET", "/api/workspace", null, CliHttpSupport.agentHeader(agent));
    }

    @Command(name = "info", mixinStandardHelpOptions = true)
    static class Info extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace", null, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "init", mixinStandardHelpOptions = true)
    static class Init extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.request(commandSpec, "POST", "/api/workspace/init", Map.of(), CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "config", description = "Get or set running config", mixinStandardHelpOptions = true)
    static class Config extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            if (fields == null || fields.isEmpty()) {
                return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/running-config", null, CliHttpSupport.agentHeader(agent));
            }
            return CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/running-config", CliHttpSupport.setBody(fields), CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "language", description = "Get or set language", mixinStandardHelpOptions = true)
    static class Language extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--agent") String agent;
        @Option(names = "--set") String language;
        public Integer call() {
            if (language == null || language.isBlank()) {
                return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/language", null, CliHttpSupport.agentHeader(agent));
            }
            return CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/language", Map.of("language", language), CliHttpSupport.agentHeader(agent));
        }
    }

    @Command(name = "files", description = "List, read, or save markdown workspace files", mixinStandardHelpOptions = true)
    static class Files extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "FILE") String file;
        @Option(names = "--content") String content;
        public Integer call() {
            if (file == null) return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/files", null);
            String path = "/api/workspace/files/" + CliHttpSupport.path(file);
            return content == null
                    ? CliHttpSupport.request(commandSpec, "GET", path, null)
                    : CliHttpSupport.request(commandSpec, "PUT", path, Map.of("content", content));
        }
    }

    @Command(name = "memory", description = "List, read, or save memory files", mixinStandardHelpOptions = true)
    static class Memory extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PATH") String file;
        @Option(names = "--content") String content;
        public Integer call() {
            if (file == null) return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/memory", null);
            String path = "/api/workspace/memory/" + CliHttpSupport.path(file);
            return content == null
                    ? CliHttpSupport.request(commandSpec, "GET", path, null)
                    : CliHttpSupport.request(commandSpec, "PUT", path, Map.of("content", content));
        }
    }

    @Command(name = "code-files", description = "List, read, or save coding files", mixinStandardHelpOptions = true)
    static class CodeFiles extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PATH") String file;
        @Option(names = "--content") String content;
        @Option(names = "--binary-url") boolean binaryUrl;
        public Integer call() {
            if (file == null) return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/code-files", null);
            String base = binaryUrl ? "/api/workspace/binary-files/" : "/api/workspace/code-files/";
            String path = base + CliHttpSupport.path(file);
            return content == null || binaryUrl
                    ? CliHttpSupport.request(commandSpec, "GET", path, null)
                    : CliHttpSupport.request(commandSpec, "PUT", path, Map.of("content", content));
        }
    }

    @Command(name = "download", mixinStandardHelpOptions = true)
    static class Download extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--save-to") Path output;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.download(commandSpec, "/api/workspace/download", output, CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "upload", mixinStandardHelpOptions = true)
    static class Upload extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--file", required = true) Path file;
        @Option(names = "--agent") String agent;
        public Integer call() { return CliHttpSupport.multipart(commandSpec, "/api/workspace/upload", file, "file", Map.of(), CliHttpSupport.agentHeader(agent)); }
    }

    @Command(name = "system-prompt-files", description = "Get or set system prompt files", mixinStandardHelpOptions = true)
    static class SystemPromptFiles extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--file", split = ",") List<String> files;
        @Option(names = "--agent") String agent;
        public Integer call() {
            return files == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/workspace/system-prompt-files", null, CliHttpSupport.agentHeader(agent))
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/system-prompt-files", files, CliHttpSupport.agentHeader(agent));
        }
    }
}
