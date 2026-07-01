package com.melon.core.plugin;

import com.melon.core.agent.WorkspaceManager;
import com.melon.plugin.api.PluginContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implementation of PluginContext for providing plugin runtime environment.
 */
class PluginContextImpl implements PluginContext {

    private final Path pluginDataDir;
    private final PluginDescriptor descriptor;
    private final WorkspaceManager workspaceManager;

    PluginContextImpl(Path pluginDataDir, PluginDescriptor descriptor, WorkspaceManager workspaceManager) {
        this.pluginDataDir = pluginDataDir;
        this.descriptor = descriptor;
        this.workspaceManager = workspaceManager;
    }

    @Override
    public Path getPluginDataDir() {
        return pluginDataDir;
    }

    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", descriptor.getId());
        config.put("name", descriptor.getDisplayName());
        config.put("version", descriptor.getVersion());
        config.put("description", descriptor.getDescription());
        return config;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, Class<T> type) {
        Object value = getConfig().get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

    @Override
    public Path getWorkspaceDir() {
        if (workspaceManager != null) {
            return workspaceManager.resolveWorkspaceDir(descriptor.getId());
        }
        return Path.of(System.getProperty("user.dir"));
    }
}
