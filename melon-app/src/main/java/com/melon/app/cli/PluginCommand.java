package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.Callable;

@Command(name = "plugin", description = "Plugin management commands", mixinStandardHelpOptions = true,
        subcommands = {
                PluginCommand.List.class,
                PluginCommand.Catalog.class,
                PluginCommand.Search.class,
                PluginCommand.Install.class,
                PluginCommand.Info.class,
                PluginCommand.Status.class,
                PluginCommand.Reload.class,
                PluginCommand.Uninstall.class,
                PluginCommand.Validate.class
        })
public class PluginCommand implements Runnable {
    public void run() { System.out.println("Usage: melonpaw plugin <subcommand> [options]"); }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class List extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_LIST); }
    }

    @Command(name = "catalog", mixinStandardHelpOptions = true)
    static class Catalog extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_CATALOG); }
    }

    @Command(name = "search", mixinStandardHelpOptions = true)
    static class Search extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_SEARCH); }
    }

    @Command(name = "install", description = "Install plugin from directory, zip, URL, or plugin://id", mixinStandardHelpOptions = true)
    static class Install extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "SOURCE") String source;
        @Option(names = "--force") boolean force;
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_INSTALL, Map.of(), Map.of("source", source, "force", force)); }
    }

    @Command(name = "info", mixinStandardHelpOptions = true)
    static class Info extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PLUGIN_ID") String pluginId;
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_INFO, Map.of("pluginId", pluginId), null); }
    }

    @Command(name = "status", mixinStandardHelpOptions = true)
    static class Status extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PLUGIN_ID") String pluginId;
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_STATUS, Map.of("pluginId", pluginId), null); }
    }

    @Command(name = "reload", mixinStandardHelpOptions = true)
    static class Reload extends AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_RELOAD); }
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true)
    static class Uninstall extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PLUGIN_ID") String pluginId;
        public Integer call() { return execute(CliCommandSpecs.PLUGIN_UNINSTALL, Map.of("pluginId", pluginId), null); }
    }

    @Command(name = "validate", description = "Validate a local plugin descriptor", mixinStandardHelpOptions = true)
    static class Validate implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PATH") Path path;

        public Integer call() {
            try {
                String descriptor = descriptorText(path);
                if (descriptor == null || descriptor.isBlank()) {
                    System.err.println("Invalid plugin: missing plugin.yaml or plugin.json");
                    return 1;
                }
                for (String key : new String[]{"id", "version"}) {
                    if (!hasKey(descriptor, key)) {
                        System.err.println("Invalid plugin: missing " + key);
                        return 1;
                    }
                }
                if (!hasKey(descriptor, "name") && !hasKey(descriptor, "displayName")) {
                    System.err.println("Invalid plugin: missing name");
                    return 1;
                }
                System.out.println("Plugin descriptor is valid: " + path);
                return 0;
            } catch (Exception e) {
                System.err.println("Invalid plugin: " + e.getMessage());
                return 1;
            }
        }

        private String descriptorText(Path path) throws Exception {
            if (Files.isDirectory(path)) {
                Path yaml = path.resolve("plugin.yaml");
                if (Files.isRegularFile(yaml)) return Files.readString(yaml);
                Path json = path.resolve("plugin.json");
                return Files.isRegularFile(json) ? Files.readString(json) : null;
            }
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry yaml = zip.getEntry("plugin.yaml");
                    ZipEntry json = zip.getEntry("plugin.json");
                    ZipEntry entry = yaml != null ? yaml : json;
                    if (entry == null) return null;
                    try (InputStream in = zip.getInputStream(entry)) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            return null;
        }

        private boolean hasKey(String descriptor, String key) {
            return descriptor.lines().anyMatch(line -> line.stripLeading().startsWith(key + ":"))
                    || descriptor.contains("\"" + key + "\"");
        }
    }
}
