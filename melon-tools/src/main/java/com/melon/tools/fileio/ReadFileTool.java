/**
 * @author melon
 */
package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import com.melon.core.util.SafePathUtil;
import com.melon.core.util.TextTruncateUtil;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件读取工具. 对应 Python file_io.py:read_file.
 */
public class ReadFileTool {

    private String workspaceDir;

    public ReadFileTool() {
        this.workspaceDir = null;
    }

    public ReadFileTool(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    @Tool(name = "read_file", description = "Read the contents of a file. Supports line range selection.", readOnly = true)
    public String readFile(
            @ToolParam(name = "file_path", description = "Path to the file to read") String filePath,
            @ToolParam(name = "start_line", description = "Starting line number (1-based, optional)") Integer startLine,
            @ToolParam(name = "end_line", description = "Ending line number (1-based, optional)") Integer endLine
    ) {
        try {
            // Resolve path from workspace context
            Path path;
            if (workspaceDir != null && !Path.of(filePath).isAbsolute()) {
                path = Path.of(workspaceDir, filePath);
            } else {
                path = Path.of(filePath);
            }

            String content = Files.readString(path);

            if (startLine != null || endLine != null) {
                String[] lines = content.split("\n");
                int start = startLine != null ? startLine - 1 : 0;
                int end = endLine != null ? endLine : lines.length;
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
