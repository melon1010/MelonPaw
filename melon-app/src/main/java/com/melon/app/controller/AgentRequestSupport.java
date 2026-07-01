package com.melon.app.controller;

final class AgentRequestSupport {
    private AgentRequestSupport() {}

    static String agentId(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }
}
