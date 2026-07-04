package com.melon.channels;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ChannelAccessControlSelfCheck {

    private ChannelAccessControlSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-channel-acl-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        ChannelAccessControlStore store = new ChannelAccessControlStore(configManager);

        if (!store.all("default").containsKey("console")) {
            throw new AssertionError("all() should initialize builtin channels");
        }

        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId("default");
        inbound.setChannel("slack");
        inbound.setUserId("u1");
        inbound.setContent("hello");
        store.addPending("default", inbound);
        if (store.pendingAll("default").size() != 1) {
            throw new AssertionError("pending user was not recorded");
        }

        if (store.allowed("default", "slack", "u1", Map.of("dm_policy", "allowlist"))) {
            throw new AssertionError("allowlist policy should block unknown user");
        }

        store.pendingAction("default", "approve", List.of(Map.of(
                "channel", "slack",
                "user_id", "u1",
                "remark", "ok",
                "username", "Alice"
        )));
        Map<String, Object> slack = store.channel("default", "slack");
        if (!slack.toString().contains("u1") || !store.pendingAll("default").isEmpty()) {
            throw new AssertionError("pending approve did not move user to whitelist: " + slack);
        }
        if (!store.allowed("default", "slack", "u1", Map.of("dm_policy", "allowlist"))) {
            throw new AssertionError("whitelisted user should pass allowlist policy");
        }

        store.updateRemark("default", "slack", "u1", "trusted");
        store.updateUsername("default", "slack", "u1", "alice-new");
        if (!store.channel("default", "slack").toString().contains("alice-new")) {
            throw new AssertionError("user metadata update was not persisted");
        }

        store.addUsers("default", "blacklist", List.of(Map.of("channel", "slack", "user_id", "u1")));
        if (store.allowed("default", "slack", "u1", Map.of("dm_policy", "open"))) {
            throw new AssertionError("blacklisted user should be blocked");
        }

        store.removeUsers("default", "blacklist", List.of(Map.of("channel", "slack", "user_id", "u1")));
        if (!store.allowed("default", "slack", "u1", Map.of("dm_policy", "open"))) {
            throw new AssertionError("blacklist removal should unblock open policy");
        }
    }
}
