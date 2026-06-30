/**
 * @author melon
 */
package com.melon.core.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers plugins from the filesystem.
 * Scans the plugins directory for plugin descriptors (plugin.yaml files).
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private static final String DESCRIPTOR_FILE = "plugin.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Path pluginsDir;

    public PluginRegistry(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    /**
     * Discovers all plugins in the plugins directory.
     *
     * @return List of plugin descriptors
     */
    public List<PluginDescriptor> discoverPlugins() {
        List<PluginDescriptor> descriptors = new ArrayList<>();

        if (!Files.exists(pluginsDir)) {
            log.debug("Plugins directory does not exist: {}", pluginsDir);
            return descriptors;
        }

        try (Stream<Path> entries = Files.list(pluginsDir)) {
            entries.filter(Files::isDirectory)
                   .forEach(pluginDir -> {
                       PluginDescriptor desc = loadDescriptor(pluginDir);
                       if (desc != null) {
                           descriptors.add(desc);
                       }
                   });
        } catch (IOException e) {
            log.error("Failed to scan plugins directory: {}", pluginsDir, e);
        }

        return descriptors;
    }

    /**
     * Loads a plugin descriptor from a directory.
     */
    private PluginDescriptor loadDescriptor(Path pluginDir) {
        Path descriptorFile = pluginDir.resolve(DESCRIPTOR_FILE);
        if (!Files.exists(descriptorFile)) {
            log.debug("No {} found in {}", DESCRIPTOR_FILE, pluginDir);
            return null;
        }

        PluginDescriptor desc = parseDescriptor(descriptorFile);
        if (desc != null) {
            desc.setPluginDir(pluginDir);
        }
        return desc;
    }

    /**
     * Parses a YAML plugin descriptor file into a PluginDescriptor using Jackson YAML.
     * <p>
     * The expected YAML structure:
     * <pre>
     * id: my-plugin
     * name: My Plugin
     * version: 1.0.0
     * description: A sample plugin
     * main_class: com.example.MyPlugin
     * </pre>
     *
     * @param descriptorFile path to the plugin.yaml file
     * @return parsed PluginDescriptor, or null if parsing fails
     */
    @SuppressWarnings("unchecked")
    private PluginDescriptor parseDescriptor(Path descriptorFile) {
        try {
            Map<String, Object> yamlMap = YAML_MAPPER.readValue(
                    descriptorFile.toFile(), Map.class);

            PluginDescriptor desc = new PluginDescriptor();
            desc.setId((String) yamlMap.get("id"));
            desc.setDisplayName((String) yamlMap.get("name"));
            desc.setVersion((String) yamlMap.get("version"));
            desc.setDescription((String) yamlMap.get("description"));
            desc.setMainClass((String) yamlMap.get("main_class"));
            return desc;
        } catch (Exception e) {
            log.error("Failed to parse plugin descriptor from {}: {}", descriptorFile, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the plugins directory.
     */
    public Path getPluginsDir() {
        return pluginsDir;
    }
}
