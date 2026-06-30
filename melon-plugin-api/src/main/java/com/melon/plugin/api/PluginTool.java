/**
 * @author melon
 */
package com.melon.plugin.api;

import reactor.core.publisher.Mono;
import java.util.Map;

import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;

/**
 * 插件工具接口。
 * 插件可提供自定义工具，通过此接口定义。
 * Melon 会将插件工具注册到 Agent 的 Toolkit 中。
 */
public interface PluginTool {
    /**
     * 工具名称
     */
    String getName();
    
    /**
     * 工具描述（展示给 LLM）
     */
    String getDescription();
    
    /**
     * 工具 JSON Schema 参数
     */
    Map<String, Object> getParameters();
    
    /**
     * 是否只读（无副作用）
     */
    default boolean isReadOnly() { return false; }
    
    /**
     * 是否并发安全
     */
    default boolean isConcurrencySafe() { return false; }
    
    /**
     * 执行工具
     */
    Mono<ToolResultBlock> callAsync(ToolCallParam param);
}
