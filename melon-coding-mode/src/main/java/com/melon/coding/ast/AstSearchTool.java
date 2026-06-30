/**
 * @author melon
 */
package com.melon.coding.ast;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * AST 搜索工具. 对应 Python ast_tool.py:ast_search.
 * ast-grep CLI 的 Java 包装.
 */
public class AstSearchTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(AstSearchTool.class);

    public AstSearchTool() {
        super(ToolBase.builder()
            .name("ast_search")
            .description("Structural code search using ast-grep. Returns matching code locations.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "pattern": { "type": "string", "description": "ast-grep pattern" },
                    "language": { "type": "string", "description": "Language (java, python, typescript, etc.)" },
                    "path": { "type": "string", "description": "File or directory to search" },
                    "max_matches": { "type": "integer", "description": "Max results", "default": 100 }
                  },
                  "required": ["pattern", "language"]
                }"""))
            .readOnly(true)
            .concurrencySafe(true));
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
        String pattern = (String) param.getInput().get("pattern");
        String language = (String) param.getInput().get("language");
        String path = (String) param.getInput().get("path");
        int maxMatches = ((Integer) param.getInput().getOrDefault("max_matches", 100));

        try {
            String result = executeAstGrep(pattern, language, path, maxMatches);
            return Mono.just(ToolResultBlock.text(result));
        } catch (Exception e) {
            log.error("ast_search failed", e);
            return Mono.just(ToolResultBlock.error("ast_search failed: " + e.getMessage()));
        }
    }

    private String executeAstGrep(String pattern, String language, String path, int maxMatches) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "ast-grep", "run",
            "--pattern", pattern,
            "--lang", language,
            "--json",
            path != null ? path : "."
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (++count >= maxMatches) break;
            }
        }

        process.waitFor();
        return output.toString();
    }
}
