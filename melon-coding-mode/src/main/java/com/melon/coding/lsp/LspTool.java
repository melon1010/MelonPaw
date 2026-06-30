/**
 * @author melon
 */
package com.melon.coding.lsp;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

/**
 * LSP 操作工具. 对应 Python lsp_tool.py:make_lsp_tool.
 * 使用 LSP4J 客户端进行代码导航.
 */
public class LspTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(LspTool.class);

    private final LspClientManager clientManager;

    /** File extension to language mapping */
    private static final Map<String, String> EXT_TO_LANG = Map.ofEntries(
        Map.entry(".java", "java"),
        Map.entry(".py", "python"),
        Map.entry(".ts", "typescript"),
        Map.entry(".tsx", "typescript"),
        Map.entry(".js", "javascript"),
        Map.entry(".jsx", "javascript"),
        Map.entry(".go", "go"),
        Map.entry(".rs", "rust"),
        Map.entry(".c", "c"),
        Map.entry(".cpp", "cpp"),
        Map.entry(".cc", "cpp"),
        Map.entry(".cxx", "cpp"),
        Map.entry(".h", "c"),
        Map.entry(".hpp", "cpp"),
        Map.entry(".cs", "csharp")
    );

    /** Operation name to LSP method mapping */
    private static final Map<String, String> OP_TO_METHOD = Map.of(
        "goToDefinition", "textDocument/definition",
        "findReferences", "textDocument/references",
        "hover", "textDocument/hover",
        "goToImplementation", "textDocument/implementation",
        "documentSymbol", "textDocument/documentSymbol",
        "workspaceSymbol", "workspace/symbol"
    );

    public LspTool(LspClientManager clientManager) {
        super(ToolBase.builder()
                .name("lsp")
                .description("LSP operations: goToDefinition, findReferences, hover, goToImplementation, documentSymbol, workspaceSymbol")
                .inputSchema(parseSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "operation": {
                              "type": "string",
                              "description": "LSP operation: goToDefinition, findReferences, hover, goToImplementation, documentSymbol, workspaceSymbol",
                              "enum": ["goToDefinition", "findReferences", "hover", "goToImplementation", "documentSymbol", "workspaceSymbol"]
                            },
                            "file_path": {
                              "type": "string",
                              "description": "Path to the source file"
                            },
                            "line": {
                              "type": "integer",
                              "description": "Line number (0-based)"
                            },
                            "character": {
                              "type": "integer",
                              "description": "Character position (0-based)"
                            },
                            "query": {
                              "type": "string",
                              "description": "Search query (for workspaceSymbol)"
                            }
                          },
                          "required": ["operation"]
                        }"""))
                .readOnly(true)
                .concurrencySafe(true));
        this.clientManager = clientManager;
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
        String operation = (String) param.getInput().get("operation");
        String filePath = (String) param.getInput().get("file_path");
        Integer line = (Integer) param.getInput().get("line");
        Integer character = (Integer) param.getInput().get("character");
        String query = (String) param.getInput().get("query");

        return Mono.fromCallable(() -> executeLsp(operation, filePath, line, character, query))
                .map(result -> ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name("lsp")
                        .output(java.util.List.of(TextBlock.builder().text(result).build()))
                        .build())
                .onErrorResume(e -> Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name("lsp")
                        .output(java.util.List.of(TextBlock.builder().text("Error: " + e.getMessage()).build()))
                        .state(ToolResultState.ERROR)
                        .build()));
    }

    private String executeLsp(String operation, String filePath, Integer line, Integer character, String query) throws Exception {
        if (operation == null || operation.isBlank()) {
            return "Error: operation is required. Supported: " + String.join(", ", OP_TO_METHOD.keySet());
        }

        // Resolve LSP method from operation name
        String method = OP_TO_METHOD.get(operation);
        if (method == null) {
            return "Error: unsupported operation '" + operation + "'. Supported: " + String.join(", ", OP_TO_METHOD.keySet());
        }

        // Determine language from file extension
        String language = null;
        String fileUri = null;
        if (filePath != null && !filePath.isBlank()) {
            language = detectLanguage(filePath);
            fileUri = Path.of(filePath).toUri().toString();
            if (language == null) {
                return "Error: cannot determine language from file path: " + filePath
                        + ". Supported extensions: " + String.join(", ", EXT_TO_LANG.keySet());
            }
        }

        // For workspace/symbol, language may still be needed
        if (language == null && "workspace/symbol".equals(method)) {
            return "Error: file_path is required to determine the language server for workspace symbol search.";
        }
        if (language == null) {
            return "Error: file_path is required for operation: " + operation;
        }

        // Ensure the LSP server is running
        if (!clientManager.isServerRunning(language)) {
            // Try to auto-start the server
            LspServerDetector detector = new LspServerDetector();
            String[] command = detector.detectServerForLanguage(language);
            if (command == null) {
                return "Error: no LSP server available for language '" + language
                        + "'. Please install the appropriate language server.";
            }

            String workspace = System.getProperty("user.dir");
            boolean started = clientManager.startServer(language, command, workspace);
            if (!started) {
                return "Error: failed to start LSP server for language '" + language
                        + "' (command: " + String.join(" ", command) + ")";
            }
            log.info("Auto-started LSP server for {}: {}", language, String.join(" ", command));
        }

        // Build LSP request params JSON
        String params = buildParamsJson(method, fileUri, line, character, query);

        // Delegate to LspClientManager
        log.info("LSP request: {} {} (language={}, file={})", operation, method, language, filePath);
        String response = clientManager.sendRequest(language, method, params);

        if (response == null || response.isBlank()) {
            return operation + " returned no results.";
        }

        return response;
    }

    /**
     * Detects the programming language from a file path extension.
     */
    private String detectLanguage(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx < 0) {
            return null;
        }
        String ext = filePath.substring(dotIdx).toLowerCase();
        return EXT_TO_LANG.get(ext);
    }

    /**
     * Builds the JSON params string for an LSP request.
     * LSP line/character positions are 0-based.
     */
    private String buildParamsJson(String method, String fileUri, Integer line, Integer character, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // textDocument operations need a textDocument and position
        if (method.startsWith("textDocument/")) {
            // textDocument
            sb.append("\"textDocument\":{\"uri\":\"").append(fileUri).append("\"}");

            // position (for operations that need it)
            if (line != null || character != null) {
                int l = line != null ? line : 0;
                int c = character != null ? character : 0;
                sb.append(",\"position\":{\"line\":").append(l).append(",\"character\":").append(c).append("}");
            }
        }

        // workspace/symbol needs a query
        if ("workspace/symbol".equals(method)) {
            String q = query != null ? query : "";
            sb.append("\"query\":\"").append(escapeJson(q)).append("\"");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Escapes special characters in a JSON string value.
     */
    private String escapeJson(String str) {
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
