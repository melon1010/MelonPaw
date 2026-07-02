package com.melon.tools.util;

import com.melon.core.util.WorkspacePathResolver;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 发送文件给用户工具. 对应 Python send_file.py.
 */
public class SendFileToUserTool {

    private final WorkspacePathResolver pathResolver;

    public SendFileToUserTool() {
        this.pathResolver = new WorkspacePathResolver(null);
    }

    public SendFileToUserTool(String workspaceDir) {
        this.pathResolver = new WorkspacePathResolver(workspaceDir);
    }

    @Tool(name = "send_file_to_user", description = "Send a file to the user for download")
    public String sendFileToUser(
            @ToolParam(name = "file_path", description = "Path to the file to send") String filePath
    ) {
        try {
            Path path = pathResolver.resolve(filePath);
            if (!Files.exists(path)) {
                return "Error: File not found: " + filePath;
            }
            String fileUrl = path.toUri().toString(); // file:// URL (RFC 8089)
            return "File sent: " + fileUrl;
        } catch (Exception e) {
            return "Error sending file: " + e.getMessage();
        }
    }
}
