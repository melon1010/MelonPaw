package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * QwenPaw frontend-compatible git API.
 */
@RestController
@RequestMapping("/api/workspace/git")
public class GitCompatController {

    private final ConfigManager configManager;

    public GitCompatController(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<?>> status(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            if (!isGitRepo(agentId)) {
                return ResponseEntity.ok(Map.of("branch", "", "changes", List.of(), "ahead", 0, "behind", 0));
            }
            String branch = git(agentId, "rev-parse", "--abbrev-ref", "HEAD").stdout().trim();
            List<Map<String, Object>> changes = new ArrayList<>();
            for (String line : git(agentId, "status", "--porcelain").stdout().split("\\R")) {
                if (line.length() < 4) continue;
                String code = line.substring(0, 2);
                String path = line.substring(3).trim();
                changes.add(Map.of(
                        "path", path,
                        "status", code.trim().isBlank() ? "modified" : code.trim(),
                        "staged", !Character.isWhitespace(code.charAt(0))
                ));
            }
            return ResponseEntity.ok(Map.of("branch", branch, "changes", changes, "ahead", 0, "behind", 0));
        });
    }

    @GetMapping("/branches")
    public Mono<ResponseEntity<?>> branches(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            if (!isGitRepo(agentId)) return ResponseEntity.ok(List.of());
            List<Map<String, Object>> result = new ArrayList<>();
            for (String line : git(agentId, "branch", "--all").stdout().split("\\R")) {
                if (line.isBlank()) continue;
                boolean current = line.startsWith("*");
                String name = line.replaceFirst("^\\*\\s*", "").trim();
                result.add(Map.of("name", name, "current", current, "remote", name.startsWith("remotes/")));
            }
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/checkout")
    public Mono<ResponseEntity<?>> checkout(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String branch = stringValue(body.get("branch"), "");
            if (branch.isBlank()) return ResponseEntity.badRequest().body(Map.of("detail", "branch is required"));
            boolean create = Boolean.TRUE.equals(body.get("create"));
            GitResult result = create ? git(agentId, "checkout", "-b", branch) : git(agentId, "checkout", branch);
            if (result.exitCode() != 0) return ResponseEntity.status(409).body(Map.of("detail", result.stderr()));
            return ResponseEntity.ok(Map.of("branch", branch));
        });
    }

    @GetMapping("/diff")
    public Mono<ResponseEntity<?>> diff(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                        @RequestParam(required = false) String path,
                                        @RequestParam(defaultValue = "false") boolean staged,
                                        @RequestParam(defaultValue = "false") boolean untracked) {
        return Mono.fromCallable(() -> {
            if (!isGitRepo(agentId)) return ResponseEntity.ok(Map.of("diff", ""));
            List<String> args = new ArrayList<>();
            args.add("diff");
            if (staged) args.add("--staged");
            if (path != null && !path.isBlank()) {
                args.add("--");
                args.add(path);
            }
            return ResponseEntity.ok(Map.of("diff", git(agentId, args.toArray(String[]::new)).stdout()));
        });
    }

    @PostMapping("/stage")
    public Mono<ResponseEntity<?>> stage(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                         @RequestBody Map<String, Object> body) {
        return runPathCommand(agentId, "add", "staged", body);
    }

    @PostMapping("/unstage")
    public Mono<ResponseEntity<?>> unstage(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @RequestBody Map<String, Object> body) {
        return runPathCommand(agentId, "reset", "unstaged", body);
    }

    @PostMapping("/commit")
    public Mono<ResponseEntity<?>> commit(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                          @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String message = stringValue(body.get("message"), "");
            if (message.isBlank()) return ResponseEntity.badRequest().body(Map.of("detail", "message is required"));
            GitResult result = git(agentId, "commit", "-m", message);
            return ResponseEntity.ok(Map.of("committed", result.exitCode() == 0, "output", result.stdout() + result.stderr()));
        });
    }

    @GetMapping("/log")
    public Mono<ResponseEntity<?>> log(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                       @RequestParam(defaultValue = "20") int limit) {
        return Mono.fromCallable(() -> {
            if (!isGitRepo(agentId)) return ResponseEntity.ok(List.of());
            String format = "%H%x1f%an%x1f%ad%x1f%s";
            GitResult result = git(agentId, "log", "--date=iso", "--pretty=format:" + format, "-" + Math.max(1, Math.min(limit, 100)));
            List<Map<String, Object>> commits = new ArrayList<>();
            for (String line : result.stdout().split("\\R")) {
                String[] parts = line.split("\\x1f", 4);
                if (parts.length == 4) {
                    commits.add(Map.of("hash", parts[0], "author", parts[1], "date", parts[2], "message", parts[3]));
                }
            }
            return ResponseEntity.ok(commits);
        });
    }

    @PostMapping("/discard")
    public Mono<ResponseEntity<?>> discard(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Discard is disabled in Java compatibility mode")));
    }

    @GetMapping("/commit-diff")
    public Mono<ResponseEntity<?>> commitDiff(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                              @RequestParam("commit_hash") String hash) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of("hash", hash, "diff", isGitRepo(agentId) ? git(agentId, "show", "--format=", hash).stdout() : "")));
    }

    @PostMapping("/revert")
    public Mono<ResponseEntity<?>> revert(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Revert is disabled in Java compatibility mode")));
    }

    private Mono<ResponseEntity<?>> runPathCommand(String agentId, String command, String responseKey, Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            List<String> paths = stringList(body.get("paths"));
            List<String> args = new ArrayList<>();
            args.add(command);
            if ("reset".equals(command)) args.add("HEAD");
            if (!paths.isEmpty()) {
                args.add("--");
                args.addAll(paths);
            } else if ("add".equals(command)) {
                args.add(".");
            }
            GitResult result = git(agentId, args.toArray(String[]::new));
            if (result.exitCode() != 0) return ResponseEntity.status(409).body(Map.of("detail", result.stderr()));
            return ResponseEntity.ok(Map.of(responseKey, paths));
        });
    }

    private boolean isGitRepo(String agentId) {
        return git(agentId, "rev-parse", "--is-inside-work-tree").exitCode() == 0;
    }

    private GitResult git(String agentId, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));
            Process process = new ProcessBuilder(command)
                    .directory(workspaceDir(agentId).toFile())
                    .redirectErrorStream(false)
                    .start();
            boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitResult(124, "", "git command timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new GitResult(process.exitValue(), stdout, stderr);
        } catch (Exception e) {
            return new GitResult(1, "", e.getMessage());
        }
    }

    private Path workspaceDir(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        if (value instanceof String text && !text.isBlank()) return List.of(text);
        return List.of();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private record GitResult(int exitCode, String stdout, String stderr) {}
}
