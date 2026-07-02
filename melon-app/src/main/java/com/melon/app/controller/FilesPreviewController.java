package com.melon.app.controller;

import com.melon.app.service.FileGuardService;
import com.melon.core.config.ConfigManager;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FilesPreviewController {

    private final ConfigManager configManager;
    private final FileGuardService fileGuardService;

    public FilesPreviewController(ConfigManager configManager, FileGuardService fileGuardService) {
        this.configManager = configManager;
        this.fileGuardService = fileGuardService;
    }

    @RequestMapping(value = "/preview/{*filePath}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public Mono<ResponseEntity<?>> preview(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String filePath) {
        return Mono.fromCallable(() -> {
            Path workspace = configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId)).toAbsolutePath().normalize();
            Path file = resolvePreviewPath(workspace, filePath);
            fileGuardService.assertAllowed(file);
            if (!fileGuardService.allowPreviewOutsideWorkspace() && !file.startsWith(workspace)) {
                return ResponseEntity.status(403).body(Map.of("detail", "OUTSIDE_WORKSPACE"));
            }
            if (!Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            String contentType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                    .body(new ByteArrayResource(Files.readAllBytes(file)));
        });
    }

    private Path resolvePreviewPath(Path workspace, String filePath) {
        String decoded = URLDecoder.decode(cleanCapturedPath(filePath), StandardCharsets.UTF_8);
        Path path = Path.of(decoded);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        Path inWorkspace = workspace.resolve(decoded).normalize();
        if (Files.exists(inWorkspace)) {
            return inWorkspace;
        }
        return Path.of("/" + decoded).toAbsolutePath().normalize();
    }

    private String cleanCapturedPath(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
