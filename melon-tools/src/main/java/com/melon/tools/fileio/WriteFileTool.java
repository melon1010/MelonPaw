package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件写入工具. 对应 Python file_io.py:write_file.
 */
public class WriteFileTool {

    @Tool(name = "write_file", description = "Write content to a file. Creates or overwrites the file.")
    public String writeFile(
            @ToolParam(name = "file_path", description = "Path to the file to write") String filePath,
            @ToolParam(name = "content", description = "Content to write to the file") String content
    ) {
        try {
            Path path = Path.of(filePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return "File written successfully: " + filePath + " (" + content.length() + " bytes)";
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }
}
