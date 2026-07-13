package com.melon.core.middleware;

import java.nio.file.Files;

public final class SystemPromptMiddlewareSelfCheck {

    private SystemPromptMiddlewareSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        String prompt = new SystemPromptMiddleware(Files.createTempDirectory("melon-prompt-check"), null)
                .onSystemPrompt(null, null, "base prompt")
                .block();
        if (prompt == null || !prompt.contains("Do not invent failed sites")) {
            throw new AssertionError("tool evidence guidance was not added: " + prompt);
        }
    }
}
