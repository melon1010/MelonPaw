package com.melon.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentScopeMessageJsonTest {

    @Test
    void consoleHistoryJsonlMessageRoundTrips() throws Exception {
        Msg original = UserMessage.builder()
                .name("user")
                .content(TextBlock.builder().text("remember this").build())
                .build();

        Msg restored = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(original), Msg.class);

        assertEquals(MsgRole.USER, restored.getRole());
        assertEquals("remember this", ((TextBlock) restored.getContent().get(0)).getText());
    }
}
