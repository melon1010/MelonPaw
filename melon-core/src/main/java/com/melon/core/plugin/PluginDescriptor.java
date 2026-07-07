package com.melon.core.plugin;

import java.nio.file.Path;

/**
 * Describes a plugin's metadata loaded from plugin.yaml.
 */
public class PluginDescriptor {

    private String id;
    private String displayName;
    private String version;
    private String description;
    private String mainClass;
    private String author;
    private String pluginType;
    private String frontendEntry;
    private Path pluginDir;

    public PluginDescriptor() {
    }

    public PluginDescriptor(String id, String displayName, String version) {
        this.id = id;
        this.displayName = displayName;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getPluginType() {
        return pluginType;
    }

    public void setPluginType(String pluginType) {
        this.pluginType = pluginType;
    }

    public String getFrontendEntry() {
        return frontendEntry;
    }

    public void setFrontendEntry(String frontendEntry) {
        this.frontendEntry = frontendEntry;
    }

    public Path getPluginDir() {
        return pluginDir;
    }

    public void setPluginDir(Path pluginDir) {
        this.pluginDir = pluginDir;
    }

    @Override
    public String toString() {
        return "PluginDescriptor{id='" + id + "', name='" + displayName + "', version='" + version + "'}";
    }
}
