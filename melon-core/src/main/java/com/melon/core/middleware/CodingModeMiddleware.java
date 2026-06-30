/**
 * @author melon
 */
package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import com.melon.core.config.CodingModeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Coding Mode 中间件. 对应 Python CodingModeMixin.
 * 注入编程工作流指南到系统提示词.
 */
public class CodingModeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CodingModeMiddleware.class);
    private final CodingModeConfig config;

    public CodingModeMiddleware(CodingModeConfig config) {
        this.config = config;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String codingGuide = """

            ## Coding Workflow

            - Track tasks using the task list tool before starting work
            - Use LSP tools (goToDefinition, findReferences, hover) for code navigation
            - Use ast_search for structural code search
            - Reference code with file:line format
            - Prefer reading files before editing
            """;
        return Mono.just(currentPrompt + codingGuide);
    }
}
