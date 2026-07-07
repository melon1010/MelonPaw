package com.melon.app.controller;

import com.melon.core.plugin.PluginManager;
import com.melon.core.plugin.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * REST controller for plugin management.
 * Corresponds to Python /api/plugins endpoints.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private static final Logger log = LoggerFactory.getLogger(PluginController.class);
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final long MAX_ZIP_BYTES = 200L * 1024L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final PluginManager pluginManager;

    public PluginController(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * Lists all loaded plugins.
     */
    @GetMapping
    public Mono<List<Map<String, Object>>> listPlugins() {
        List<Map<String, Object>> plugins = pluginManager.discoverPlugins().stream()
            .map(this::pluginInfo)
            .collect(Collectors.toList());
        return Mono.just(plugins);
    }

    @GetMapping("/catalog")
    public Mono<ResponseEntity<?>> catalog() {
        Map<String, Object> catalog = new java.util.LinkedHashMap<>();
        catalog.put("updated_at", null);
        catalog.put("plugins", pluginManager.discoverPlugins().stream().map(this::catalogEntry).toList());
        catalog.put("error", null);
        return Mono.just(ResponseEntity.ok(catalog));
    }

    @GetMapping("/market/search")
    public Mono<ResponseEntity<?>> marketSearch() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "success", true,
                "message", "ok",
                "data", Map.of("total", 0, "plugins", List.of())
        )));
    }

    @PostMapping("/install")
    public Mono<ResponseEntity<?>> installPlugin(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(installFromSource(body)))
                .onErrorResume(e -> Mono.just(badRequest(e)));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadPlugin(@RequestPart("file") FilePart filePart) {
        return Mono.<ResponseEntity<?>>defer(() -> {
            Path path;
            try {
                path = Files.createTempFile("melon-plugin-upload-", ".zip");
            } catch (Exception e) {
                return Mono.error(e);
            }
            return filePart.transferTo(path)
                    .then(Mono.fromCallable(() -> (ResponseEntity<?>) ResponseEntity.ok(installZip(path, true))))
                    .doFinally(signal -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }).onErrorResume(e -> {
            log.warn("Plugin upload failed: {}", e.getMessage());
            return Mono.just(badRequest(e));
        });
    }

    /**
     * Gets details for a specific plugin.
     */
    @GetMapping("/{pluginId}")
    public Mono<Map<String, Object>> getPlugin(@PathVariable String pluginId) {
        return Mono.just(pluginManager.discoverPlugins().stream()
                .filter(desc -> pluginId.equals(desc.getId()))
                .findFirst()
                .map(this::pluginInfo)
                .orElseGet(() -> Map.of("error", "Plugin not found: " + pluginId)));
    }

    @GetMapping("/{pluginId}/status")
    public Mono<ResponseEntity<?>> pluginStatus(@PathVariable String pluginId) {
        boolean loaded = pluginManager.getPlugin(pluginId) != null;
        return Mono.just(ResponseEntity.ok(Map.of("id", pluginId, "enabled", loaded, "loaded", loaded)));
    }

    /**
     * Reloads all plugins.
     */
    @PostMapping("/reload")
    public Mono<Map<String, Object>> reloadPlugins() {
        pluginManager.unloadAll();
        pluginManager.loadAll();
        return Mono.just(Map.of(
            "status", "reloaded",
            "count", pluginManager.getPluginIds().size()
        ));
    }

    /**
     * Unloads a specific plugin.
     */
    @DeleteMapping("/{pluginId}")
    public Mono<Map<String, Object>> unloadPlugin(@PathVariable String pluginId) {
        return Mono.fromCallable(() -> {
            boolean deleted = pluginManager.uninstallInstalledPlugin(pluginId);
            return Map.of("status", deleted ? "uninstalled" : "not_found", "id", pluginId, "deleted", deleted);
        });
    }

    private Map<String, Object> installFromSource(Map<String, Object> body) throws Exception {
        String source = stringValue(body != null ? body.get("source") : null);
        boolean force = Boolean.TRUE.equals(body != null ? body.get("force") : null);
        if (source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            Path zip = downloadRemoteZip(source);
            try {
                return installZip(zip, force);
            } finally {
                Files.deleteIfExists(zip);
            }
        }
        if (source.startsWith("plugin://")) {
            return installExisting(source.substring("plugin://".length()));
        }
        Path path = Path.of(source).toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return installDirectory(path, force);
        }
        if (Files.isRegularFile(path)) {
            return installZip(path, force);
        }
        throw new java.nio.file.NoSuchFileException("Plugin source not found: " + source);
    }

    private Path downloadRemoteZip(String source) throws Exception {
        URI uri = URI.create(source);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("Plugin download failed: HTTP " + response.statusCode());
        }
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (declared > MAX_ZIP_BYTES) {
            throw new IllegalArgumentException("Plugin archive is too large");
        }
        Path zip = Files.createTempFile("melon-plugin-remote-", ".zip");
        boolean ok = false;
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(zip)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("Plugin archive is too large");
                }
                output.write(buffer, 0, read);
            }
            ok = true;
            return zip;
        } finally {
            if (!ok) {
                Files.deleteIfExists(zip);
            }
        }
    }

    private Map<String, Object> installExisting(String pluginId) throws Exception {
        PluginDescriptor descriptor = pluginManager.loadInstalledPlugin(pluginId);
        return installResult(descriptor, "Plugin loaded");
    }

    private Map<String, Object> installZip(Path zipFile, boolean force) throws Exception {
        Path tmp = Files.createTempDirectory("melon-plugin-zip-");
        try {
            extractZip(zipFile, tmp);
            Path root = findPluginRoot(tmp);
            return installDirectory(root, force);
        } finally {
            deleteDirectory(tmp);
        }
    }

    private Map<String, Object> installDirectory(Path source, boolean force) throws Exception {
        PluginDescriptor descriptor = pluginManager.readDescriptor(source);
        if (descriptor == null || stringValue(descriptor.getId()).isBlank()) {
            throw new IllegalArgumentException("plugin.yaml with id is required");
        }
        String id = descriptor.getId();
        if (id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("Invalid plugin id: " + id);
        }
        Path pluginsDir = pluginManager.getPluginsDir().toAbsolutePath().normalize();
        Path target = pluginsDir.resolve(id).normalize();
        if (!target.startsWith(pluginsDir)) {
            throw new IllegalArgumentException("Invalid plugin id: " + id);
        }
        if (Files.exists(target) && !samePath(source, target)) {
            if (!force) {
                throw new IllegalStateException("Plugin already exists: " + id);
            }
            pluginManager.uninstallInstalledPlugin(id);
        }
        if (!samePath(source, target)) {
            Files.createDirectories(pluginsDir);
            copyDirectory(source, target);
        }
        PluginDescriptor loaded = pluginManager.loadInstalledPlugin(id);
        return installResult(loaded, "Plugin installed");
    }

    private Map<String, Object> installResult(PluginDescriptor descriptor, String message) {
        Map<String, Object> result = new LinkedHashMap<>(pluginInfo(descriptor));
        result.put("message", message);
        return result;
    }

    private Map<String, Object> pluginInfo(PluginDescriptor descriptor) {
        var plugin = pluginManager.getPlugin(descriptor.getId());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", descriptor.getId());
        info.put("name", firstNonBlank(
                plugin != null ? plugin.getDisplayName() : null,
                descriptor.getDisplayName(),
                descriptor.getId()
        ));
        info.put("version", firstNonBlank(plugin != null ? plugin.getVersion() : null, descriptor.getVersion()));
        info.put("description", stringValue(descriptor.getDescription()));
        info.put("author", stringValue(descriptor.getAuthor()));
        info.put("enabled", true);
        info.put("loaded", plugin != null);
        info.put("plugin_type", firstNonBlank(descriptor.getPluginType(), "general"));
        if (!stringValue(descriptor.getFrontendEntry()).isBlank()) {
            info.put("frontend_entry", descriptor.getFrontendEntry());
        }
        return info;
    }

    private Map<String, Object> catalogEntry(PluginDescriptor descriptor) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", descriptor.getId());
        entry.put("plugin_id", descriptor.getId());
        entry.put("name", firstNonBlank(descriptor.getDisplayName(), descriptor.getId()));
        entry.put("description", stringValue(descriptor.getDescription()));
        entry.put("version", stringValue(descriptor.getVersion()));
        entry.put("author", stringValue(descriptor.getAuthor()));
        entry.put("kind", firstNonBlank(descriptor.getPluginType(), "general"));
        entry.put("size", "");
        entry.put("sha256", "");
        entry.put("install_url", "plugin://" + descriptor.getId());
        entry.put("installed", true);
        entry.put("installed_version", stringValue(descriptor.getVersion()));
        entry.put("upgrade_available", false);
        return entry;
    }

    private Path findPluginRoot(Path root) throws Exception {
        if (Files.exists(root.resolve("plugin.yaml"))) {
            return root;
        }
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 3)) {
            stream.filter(path -> path.getFileName().toString().equals("plugin.yaml"))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .forEach(path -> matches.add(path.getParent()));
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No plugin.yaml found in archive");
        }
        return matches.get(0);
    }

    private void extractZip(Path zipFile, Path targetDir) throws Exception {
        int entries = 0;
        long totalBytes = 0L;
        try (InputStream input = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("Plugin archive has too many entries");
                }
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir.normalize())) {
                    throw new IllegalArgumentException("Plugin archive contains unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long copied = Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                totalBytes += copied;
                if (totalBytes > MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("Plugin archive is too large");
                }
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path dst = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(path, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean samePath(Path a, Path b) {
        return Objects.equals(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
    }

    private ResponseEntity<?> badRequest(Throwable e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage() == null ? "Bad request" : e.getMessage()));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
