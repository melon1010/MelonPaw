package com.melon.channels;

import java.util.Map;

public final class IMessageChannelAdapterSelfCheck {

    private IMessageChannelAdapterSelfCheck() {
    }

    public static void main(String[] args) {
        IMessageChannelAdapter adapter = new IMessageChannelAdapter();
        ChannelHealth health = adapter.health("default", Map.of(
                "enabled", true,
                "db_path", "/definitely/missing/chat.db"
        ));
        if (!"misconfigured".equals(health.getStatus()) || health.isAvailable()) {
            throw new AssertionError("iMessage missing requirements should be misconfigured: " + health.toMap());
        }
        if (!adapter.implemented()) {
            throw new AssertionError("iMessage adapter should be implemented with runtime health gating");
        }
    }
}
