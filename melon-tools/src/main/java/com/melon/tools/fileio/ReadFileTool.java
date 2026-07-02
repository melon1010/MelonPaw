package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import com.melon.core.util.TextTruncateUtil;
import com.melon.core.util.WorkspacePathResolver;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件读取工具. 对应 Python file_io.py:read_file.
 */
public class ReadFileTool {

    private final WorkspacePathResolver pathResolver;

    public ReadFileTool() {
        this.pathResolver = new WorkspacePathResolver(null);
    }

    public ReadFileTool(String workspaceDir) {
        this.pathResolver = new WorkspacePathResolver(workspaceDir);
    }

    @Tool(name = "read_file", description = "Read the contents of a file. Supports line range selection.", readOnly = true)
    public String readFile(
            @ToolParam(name = "file_path", description = "Path to the file to read") String filePath,
            @ToolParam(name = "start_line", description = "Starting line number (1-based, optional)") Integer startLine,
            @ToolParam(name = "end_line", description = "Ending line number (1-based, optional)") Integer endLine
    ) {
        try {
            Path path = pathResolver.resolve(filePath);
            String content = Files.readString(path);

            if (startLine != null || endLine != null) {
                String[] lines = content.split("\n");
                int start = Math.max(0, startLine != null ? startLine - 1 : 0);
                int end = Math.min(lines.length, Math.max(start, endLine != null ? endLine : lines.length));
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < end && i < lines.length; i++) {
                    sb.append(i + 1).append("\t").append(lines[i]).append("\n");
                }
                content = sb.toString();
            }

            return TextTruncateUtil.truncate(content, TextTruncateUtil.DEFAULT_MAX_BYTES,
                    "<<<TRUNCATED>>> Use start_line to read more.");
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}
