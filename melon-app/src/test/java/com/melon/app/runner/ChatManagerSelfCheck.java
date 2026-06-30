package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChatManagerSelfCheck {

    private ChatManagerSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-chat-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        ChatManager manager = new ChatManager(configManager);
        ChatSpec chat = manager.create("default", "self-check", "test-model");
        ChatSpec updated = manager.appendMessage(chat.getSessionId(), "user", "hello");

        if (updated == null) {
            throw new AssertionError("appendMessage should resolve chat by session_id");
        }
        if (!chat.getId().equals(updated.getId())) {
            throw new AssertionError("appendMessage wrote to the wrong chat");
        }
        if (manager.get(chat.getId()).getMessages().size() != 1) {
            throw new AssertionError("message was not persisted");
        }
    }
}
