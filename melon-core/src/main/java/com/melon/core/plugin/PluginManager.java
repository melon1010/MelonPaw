/**
 * @author melon
 */
package com.melon.core.plugin;

import com.melon.core.agent.WorkspaceManager;
import com.melon.plugin.api.PluginContext;
import com.melon.plugin.api.MelonPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages plugin lifecycle: discovery, loading, initialization, and destruction.
 * Corresponds to Python plugin system.
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Path pluginsDir;
    private final WorkspaceManager workspaceManager;
    private final Map<String, MelonPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, PluginContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final PluginRegistry registry;

    public PluginManager(Path pluginsDir) {
        this(pluginsDir, null);
    }

    public PluginManager(Path pluginsDir, WorkspaceManager workspaceManager) {
        this.pluginsDir = pluginsDir;
        this.workspaceManager = workspaceManager;
        this.registry = new PluginRegistry(pluginsDir);
    }

    /**
     * Discovers and loads all plugins from the plugins directory.
     */
    public void loadAll() {
        List<PluginDescriptor> descriptors = registry.discoverPlugins();
        for (PluginDescriptor desc : descriptors) {
            try {
                loadPlugin(desc);
            } catch (Exception e) {
                log.error("Failed to load plugin '{}': {}", desc.getId(), e.getMessage(), e);
            }
        }
        log.info("Loaded {} plugins", plugins.size());
    }

    /**
     * Loads a single plugin from its descriptor using URLClassLoader.
     * <p>
     * Scans the plugin directory for JAR files, creates a dedicated
     * {@link URLClassLoader}, loads the {@code main_class} via
     * {@link Class#forName(String, boolean, ClassLoader)}, and instantiates
     * it using {@code getDeclaredConstructor().newInstance()}.
     *
     * @param descriptor the plugin descriptor containing id, main_class, and plugin directory
     * @throws Exception if the plugin cannot be loaded or initialized
     */
    public void loadPlugin(PluginDescriptor descriptor) throws Exception {
        String pluginId = descriptor.getId();
        if (plugins.containsKey(pluginId)) {
            log.warn("Plugin '{}' already loaded, skipping", pluginId);
            return;
        }

        log.info("Loading plugin: {} v{} ({})", pluginId, descriptor.getVersion(), descriptor.getDisplayName());

        Path pluginDir = descriptor.getPluginDir();
        if (pluginDir == null) {
            log.warn("Plugin '{}' has no plugin directory, skipping", pluginId);
            return;
        }

        // Find JAR files in the plugin directory
        File[] jarFiles = pluginDir.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            log.warn("No JAR files found for plugin '{}' in {}", pluginId, pluginDir);
            return;
        }

        // Create URLClassLoader for the plugin JARs
        URL[] urls = new URL[jarFiles.length];
        for (int i = 0; i < jarFiles.length; i++) {
            urls[i] = jarFiles[i].toURI().toURL();
            log.debug("Plugin '{}' adding JAR: {}", pluginId, jarFiles[i]);
        }

        String mainClass = descriptor.getMainClass();
        if (mainClass == null || mainClass.isBlank()) {
            log.warn("Plugin '{}' has no main_class specified, skipping", pluginId);
            return;
        }

        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
        try {
            Class<?> clazz = Class.forName(mainClass, true, classLoader);
            Object instance = clazz.getDeclaredConstructor().newInstance();

            if (instance instanceof MelonPlugin plugin) {
                if (!pluginId.equals(plugin.getId())) {
                    log.warn("Plugin ID mismatch: descriptor={}, plugin={}", pluginId, plugin.getId());
                }
                PluginContext context = createPluginContext(descriptor);
                plugin.init(context);
                plugins.put(pluginId, plugin);
                contexts.put(pluginId, context);
                classLoaders.put(pluginId, classLoader);
                log.info("Plugin '{}' initialized successfully", pluginId);
            } else {
                log.error("Plugin '{}' main class '{}' does not implement MelonPlugin", pluginId, mainClass);
                classLoader.close();
            }
        } catch (Exception e) {
            log.error("Failed to load plugin '{}' main class '{}': {}", pluginId, mainClass, e.getMessage(), e);
            classLoader.close();
            throw e;
        }
    }

    /**
     * Unloads a plugin by ID.
     */
    public void unloadPlugin(String pluginId) {
        MelonPlugin plugin = plugins.remove(pluginId);
        if (plugin != null) {
            try {
                plugin.destroy();
            } catch (Exception e) {
                log.error("Error destroying plugin '{}': {}", pluginId, e.getMessage(), e);
            }
            contexts.remove(pluginId);

            // Close the plugin's classloader
            URLClassLoader cl = classLoaders.remove(pluginId);
            if (cl != null) {
                try {
                    cl.close();
                } catch (Exception e) {
                    log.debug("Error closing classloader for plugin '{}': {}", pluginId, e.getMessage());
                }
            }

            log.info("Plugin '{}' unloaded", pluginId);
        }
    }

    /**
     * Unloads all plugins.
     */
    public void unloadAll() {
        new ArrayList<>(plugins.keySet()).forEach(this::unloadPlugin);
    }

    /**
     * Returns a plugin by ID.
     */
    public MelonPlugin getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    /**
     * Returns all loaded plugins.
     */
    public Collection<MelonPlugin> getAllPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    /**
     * Returns all loaded plugin IDs.
     */
    public Set<String> getPluginIds() {
        return Collections.unmodifiableSet(plugins.keySet());
    }

    private PluginContext createPluginContext(PluginDescriptor descriptor) {
        Path pluginDataDir = pluginsDir.resolve(descriptor.getId()).resolve("data");
        try {
            java.nio.file.Files.createDirectories(pluginDataDir);
        } catch (Exception e) {
            log.warn("Failed to create plugin data dir: {}", pluginDataDir);
        }
        return new PluginContextImpl(pluginDataDir, descriptor, workspaceManager);
    }
}
