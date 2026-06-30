/**
 * @author melon
 */
package com.melon.tools.skill;

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
import java.util.stream.Stream;

/**
 * Materializes a skill by reading skill definition files from the workspace
 * and returning the content for the agent to use.
 * Corresponds to Python materialize_skill tool.
 * Always enabled in the toolkit.
 */
public class MaterializeSkillTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(MaterializeSkillTool.class);

    private final Path workspaceDir;

    public MaterializeSkillTool(Path workspaceDir) {
        super(ToolBase.builder()
            .name("materialize_skill")
            .description("Read and materialize a skill definition from the workspace skills directory. Returns the skill content for immediate use.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "skill_name": {
                      "type": "string",
                      "description": "Name of the skill to materialize (directory name under .melon/skills/)"
                    },
                    "file_path": {
                      "type": "string",
                      "description": "Optional specific file within the skill directory. Defaults to SKILL.md"
                    }
                  },
                  "required": ["skill_name"]
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
        String skillName = (String) param.getInput().get("skill_name");
        String filePath = (String) param.getInput().getOrDefault("file_path", "SKILL.md");

        if (skillName == null || skillName.isBlank()) {
            return Mono.just(ToolResultBlock.error("skill_name is required"));
        }

        // Skills are stored under ~/.melon/skills/{skill_name}/
        Path skillsDir = workspaceDir.resolve(".melon").resolve("skills");
        Path skillDir = skillsDir.resolve(skillName);
        Path targetFile = skillDir.resolve(filePath);

        // Safety check
        Path resolved = SafePathUtil.resolveSafe(skillsDir, skillName + "/" + filePath);
        if (resolved == null) {
            return Mono.just(ToolResultBlock.error("Path traversal detected in skill path"));
        }

        if (!Files.exists(targetFile)) {
            // List available skills
            if (Files.exists(skillsDir)) {
                try (Stream<Path> entries = Files.list(skillsDir)) {
                    var available = entries
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .toList();
                    return Mono.just(ToolResultBlock.error("Skill '" + skillName + "' not found. Available skills: " + available));
                } catch (Exception e) {
                    log.error("Failed to list skills", e);
                }
            }
            return Mono.just(ToolResultBlock.error("Skill '" + skillName + "' not found. No skills directory exists at " + skillsDir));
        }

        try {
            String content = Files.readString(targetFile);
            return Mono.just(ToolResultBlock.text(content));
        } catch (Exception e) {
            log.error("Failed to read skill file: {}", targetFile, e);
            return Mono.just(ToolResultBlock.error("Failed to read skill file: " + e.getMessage()));
        }
    }
}
