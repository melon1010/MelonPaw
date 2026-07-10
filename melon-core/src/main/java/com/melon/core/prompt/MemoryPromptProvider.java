package com.melon.core.prompt;

/**
 * 记忆指导提示词生成. 对应 Python memory/prompts.py.
 */
public class MemoryPromptProvider {

    private static final String MEMORY_GUIDANCE_ZH = """
        <!-- memory:start -->
        你拥有持久化记忆系统。`memory_search` 会对 MEMORY.md 和 memory/*.md 做关键词检索；
        优先使用短关键词、标识符、文件名、日期、人名、错误码，而不是宽泛自然语言问题。
        使用 `memory_get` 读取 `memory_search` 命中的特定记忆文件上下文。
        <!-- memory:end -->""";

    private static final String MEMORY_GUIDANCE_EN = """
        <!-- memory:start -->
        You have access to a persistent memory system.
        `memory_search` performs keyword search over MEMORY.md and memory/*.md; use short,
        specific keywords, identifiers, file names, dates, people, or error codes instead of
        broad natural-language questions.
        Use `memory_get` after `memory_search` to read surrounding lines from specific memory files.
        <!-- memory:end -->""";

    public String getMemoryPrompt(String language) {
        if (language != null && language.startsWith("zh")) {
            return MEMORY_GUIDANCE_ZH;
        }
        return MEMORY_GUIDANCE_EN;
    }
}
