package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import com.melon.core.util.WorkspacePathResolver;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件编辑工具. 对应 Python file_io.py:edit_file.
 * 查找替换模式, 替换所有匹配.
 */
public class EditFileTool {

    private final WorkspacePathResolver pathResolver;

    public EditFileTool() {
        this.pathResolver = new WorkspacePathResolver(null);
    }

    public EditFileTool(String workspaceDir) {
        this.pathResolver = new WorkspacePathResolver(workspaceDir);
    }

    @Tool(name = "edit_file", description = "Edit a file by finding and replacing text. Replaces all occurrences.")
    public String editFile(
            @ToolParam(name = "file_path", description = "Path to the file to edit") String filePath,
            @ToolParam(name = "old_text", description = "Text to find in the file") String oldText,
            @ToolParam(name = "new_text", description = "Text to replace with") String newText
    ) {
        try {
            Path path = pathResolver.resolve(filePath);
            String content = Files.readString(path);
            if (!content.contains(oldText)) {
                return "Error: old_text not found in file";
            }
            String newContent = content.replace(oldText, newText);
            Files.writeString(path, newContent);
            int count = countOccurrences(content, oldText);
            return "File edited successfully: " + count + " replacement(s) in " + filePath;
        } catch (Exception e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
