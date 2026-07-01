package com.melon.core.prompt;

/**
 * 记忆指导提示词生成. 对应 Python memory/prompts.py.
 */
public class MemoryPromptProvider {

    private static final String MEMORY_GUIDANCE_ZH = """
        <!-- memory:start -->
        你拥有持久化记忆系统。使用 `memory_search` 工具检索过去的决策和上下文。
        使用 `memory_get` 读取特定记忆文件。重要的事实和决策会被自动提取到 MEMORY.md。
        <!-- memory:end -->""";

    private static final String MEMORY_GUIDANCE_EN = """
        <!-- memory:start -->
        You have access to a persistent memory system.
        Use the `memory_search` tool to recall past decisions and context.
        Use `memory_get` to read specific memory files.
        <!-- memory:end -->""";

    public String getMemoryPrompt(String language) {
        if (language != null && language.startsWith("zh")) {
            return MEMORY_GUIDANCE_ZH;
        }
        return MEMORY_GUIDANCE_EN;
    }
}
