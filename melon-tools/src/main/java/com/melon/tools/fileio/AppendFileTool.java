package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件追加工具. 对应 Python file_io.py:append_file.
 * <p>
 * 将内容追加到文件末尾. 若文件不存在则创建.
 */
public class AppendFileTool {

    @Tool(name = "append_file",
          description = "Append content to the end of a file. Creates the file if it does not exist.")
    public String appendFile(
            @ToolParam(name = "file_path", description = "Path to the file to append to") String filePath,
            @ToolParam(name = "content", description = "Content to append to the file") String content
    ) {
        try {
            Path path = Path.of(filePath);
            // 确保父目录存在
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            // 追加写入: CREATE + APPEND
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "Content appended successfully: " + filePath + " (" + content.length() + " bytes)";
        } catch (Exception e) {
            return "Error appending to file: " + e.getMessage();
        }
    }
}
