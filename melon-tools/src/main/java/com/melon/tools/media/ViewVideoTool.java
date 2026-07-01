package com.melon.tools.media;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.util.SafePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads a video file metadata and returns information for the LLM.
 * Corresponds to Python view_video tool.
 * Note: Video content analysis depends on model multimodal capabilities.
 */
public class ViewVideoTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(ViewVideoTool.class);
    private static final long MAX_VIDEO_SIZE = 50 * 1024 * 1024; // 50MB

    private final Path workspaceDir;

    public ViewVideoTool(Path workspaceDir) {
        super(ToolBase.builder()
            .name("view_video")
            .description("Read a video file from the workspace and return metadata for analysis.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "Path to the video file"
                    }
                  },
                  "required": ["file_path"]
                }"""))
            .readOnly(true)
            .concurrencySafe(true));
        this.workspaceDir = workspaceDir;
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String filePath = (String) param.getInput().get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return Mono.just(ToolResultBlock.error("file_path is required"));
        }

        Path resolved = SafePathUtil.resolveSafe(workspaceDir, filePath);
        if (resolved == null) {
            return Mono.just(ToolResultBlock.error("Path traversal detected: " + filePath));
        }
        if (!Files.exists(resolved)) {
            return Mono.just(ToolResultBlock.error("File not found: " + filePath));
        }

        try {
            long size = Files.size(resolved);
            if (size > MAX_VIDEO_SIZE) {
                return Mono.just(ToolResultBlock.error("Video too large (" + size + " bytes). Max: " + MAX_VIDEO_SIZE));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Video file: ").append(resolved.getFileName()).append("\n");
            sb.append("Size: ").append(size).append(" bytes (").append(size / 1024 / 1024).append(" MB)\n");
            sb.append("Path: ").append(resolved.toAbsolutePath()).append("\n");
            sb.append("[Video content analysis depends on model multimodal capabilities]");

            return Mono.just(ToolResultBlock.text(sb.toString()));
        } catch (Exception e) {
            log.error("Failed to read video: {}", filePath, e);
            return Mono.just(ToolResultBlock.error("Failed to read video: " + e.getMessage()));
        }
    }
}
