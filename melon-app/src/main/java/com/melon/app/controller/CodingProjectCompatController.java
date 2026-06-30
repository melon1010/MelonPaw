/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * QwenPaw frontend-compatible coding project API.
 */
@RestController
@RequestMapping("/api/workspace/coding-project")
public class CodingProjectCompatController {

    private final ConfigManager configManager;

    public CodingProjectCompatController(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> getProject() {
        return Mono.fromCallable(() -> ResponseEntity.ok(projectInfo(activePath())));
    }

    @PutMapping
    public Mono<ResponseEntity<?>> setProject(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String path = body == null || body.get("path") == null ? "" : String.valueOf(body.get("path"));
            Path active = path.isBlank() ? defaultWorkspace() : expand(path);
            Files.createDirectories(active);
            JsonUtils.save(stateFile(), Map.of("path", active.toString()));
            return ResponseEntity.ok(projectInfo(active));
        });
    }

    @PostMapping("/create")
    public Mono<ResponseEntity<?>> createProject(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String name = sanitizeName(String.valueOf(body.getOrDefault("name", "project")));
            Path project = projectsDir().resolve(name).normalize();
            Files.createDirectories(project);
            if (!Files.exists(project.resolve(".git"))) {
                run(project, "git", "init");
            }
            JsonUtils.save(stateFile(), Map.of("path", project.toString()));
            return ResponseEntity.ok(Map.of("path", project.toString(), "name", project.getFileName().toString()));
        });
    }

    @GetMapping("/list")
    public Mono<ResponseEntity<?>> listProjects() {
        return Mono.fromCallable(() -> {
            Path projectsDir = projectsDir();
            Files.createDirectories(projectsDir);
            Path active = activePath();
            List<Map<String, Object>> result = new ArrayList<>();
            try (var stream = Files.list(projectsDir)) {
                stream.filter(Files::isDirectory).forEach(path -> result.add(Map.of(
                        "path", path.toString(),
                        "name", path.getFileName().toString(),
                        "is_git", Files.exists(path.resolve(".git")),
                        "is_active", path.equals(active)
                )));
            }
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/import-local")
    public Mono<ResponseEntity<?>> importLocal(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String source = String.valueOf(body.getOrDefault("path", ""));
            if (source.isBlank()) return ResponseEntity.badRequest().body(Map.of("detail", "path is required"));
            Path sourcePath = expand(source);
            if (!Files.isDirectory(sourcePath)) return ResponseEntity.status(409).body(Map.of("detail", "path is not a directory"));
            JsonUtils.save(stateFile(), Map.of("path", sourcePath.toString()));
            return ResponseEntity.ok(Map.of("path", sourcePath.toString(), "name", sourcePath.getFileName().toString()));
        });
    }

    @PostMapping(value = "/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadZip(@RequestPart("file") FilePart filePart, @RequestParam String name) {
        return Mono.just(ResponseEntity.status(501).body(Map.of(
                "detail", "Project zip import is not implemented in Java compatibility mode",
                "enabled", false
        )));
    }

    @GetMapping("/browse-dirs")
    public Mono<ResponseEntity<?>> browseDirs(@RequestParam(defaultValue = "~") String path,
                                              @RequestParam(defaultValue = "false") boolean showHidden) {
        return Mono.fromCallable(() -> {
            Path current = expand(path);
            if (!Files.isDirectory(current)) current = current.getParent() != null ? current.getParent() : Path.of(System.getProperty("user.home"));
            List<Map<String, Object>> dirs = new ArrayList<>();
            try (var stream = Files.list(current)) {
                stream.filter(Files::isDirectory)
                        .filter(dir -> showHidden || !dir.getFileName().toString().startsWith("."))
                        .sorted()
                        .forEach(dir -> dirs.add(Map.of("name", dir.getFileName().toString(), "path", dir.toString())));
            }
            Path parent = current.getParent();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current", current.toString());
            result.put("parent", parent != null ? parent.toString() : null);
            result.put("dirs", dirs);
            result.put("selectable", true);
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping(value = "/clone", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> cloneProject(@RequestBody Map<String, Object> body) {
        return Flux.defer(() -> {
            String url = String.valueOf(body.getOrDefault("url", ""));
            if (url.isBlank()) {
                return Flux.just(ServerSentEvent.builder(Map.<String, Object>of("type", "error", "message", "url is required")).build());
            }
            String name = sanitizeName(String.valueOf(body.getOrDefault("name", url.substring(url.lastIndexOf('/') + 1).replaceFirst("\\.git$", ""))));
            Path projectsDir = projectsDir();
            Path target = projectsDir.resolve(name).normalize();
            try {
                Files.createDirectories(projectsDir);
                Process process = new ProcessBuilder("git", "clone", url, target.toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!finished) {
                    process.destroyForcibly();
                    return Flux.just(ServerSentEvent.builder(Map.<String, Object>of("type", "error", "message", "git clone timed out")).build());
                }
                if (process.exitValue() != 0) {
                    return Flux.just(ServerSentEvent.builder(Map.<String, Object>of("type", "error", "message", output)).build());
                }
                JsonUtils.save(stateFile(), Map.of("path", target.toString()));
                return Flux.just(
                        ServerSentEvent.builder(Map.<String, Object>of("type", "log", "message", output)).build(),
                        ServerSentEvent.builder(Map.<String, Object>of("type", "done", "path", target.toString(), "name", name)).build()
                );
            } catch (Exception e) {
                return Flux.just(ServerSentEvent.builder(Map.<String, Object>of("type", "error", "message", e.getMessage())).build());
            }
        });
    }

    private Map<String, Object> projectInfo(Path path) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("path", path.toString());
        info.put("name", path.getFileName() != null ? path.getFileName().toString() : path.toString());
        info.put("is_workspace_default", path.equals(defaultWorkspace()));
        info.put("workspace_dir", defaultWorkspace().toString());
        info.put("exists", Files.exists(path));
        return info;
    }

    private Path activePath() {
        Object raw = JsonUtils.loadAsMap(stateFile()).get("path");
        if (raw instanceof String path && !path.isBlank()) return expand(path);
        return defaultWorkspace();
    }

    private Path defaultWorkspace() {
        return configManager.resolveWorkspaceDir("default");
    }

    private Path stateFile() {
        return configManager.resolveHomeDir().resolve("coding-project.json");
    }

    private Path projectsDir() {
        return configManager.resolveHomeDir().resolve("coding_projects");
    }

    private Path expand(String path) {
        if (path == null || path.isBlank() || "~".equals(path)) return Path.of(System.getProperty("user.home"));
        if (path.startsWith("~/")) return Path.of(System.getProperty("user.home")).resolve(path.substring(2)).normalize();
        return Path.of(path).normalize();
    }

    private String sanitizeName(String name) {
        String sanitized = name == null ? "project" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.isBlank() ? "project" : sanitized;
    }

    private void run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
        process.waitFor(15, TimeUnit.SECONDS);
    }
}
