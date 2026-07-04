package com.melon.channels;

import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class MqttChannelAdapterSelfCheck {

    private MqttChannelAdapterSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        MqttChannelAdapter adapter = new MqttChannelAdapter();
        ChannelHealth missing = adapter.health("default", Map.of("enabled", true));
        if (!"misconfigured".equals(missing.getStatus()) || missing.isAvailable()) {
            throw new AssertionError("missing mqtt config should be misconfigured");
        }

        Method inbound = MqttChannelAdapter.class.getDeclaredMethod("inbound", String.class, String.class, MqttMessage.class);
        inbound.setAccessible(true);
        MqttMessage mqtt = new MqttMessage("""
                {"user_id":"u1","session_id":"s1","content":"hello"}
                """.getBytes(StandardCharsets.UTF_8));
        ChannelInboundMessage message = (ChannelInboundMessage) inbound.invoke(adapter, "default", "server/u1/up", mqtt);
        if (!"u1".equals(message.getUserId())
                || !"s1".equals(message.getSessionId())
                || !"hello".equals(message.getContent())
                || !"server/u1/up".equals(message.getReplyTo().getId())) {
            throw new AssertionError("mqtt payload was not converted to inbound message");
        }

        Method publishTopic = MqttChannelAdapter.class.getDeclaredMethod("publishTopic", ChannelOutboundMessage.class, Map.class);
        publishTopic.setAccessible(true);
        ChannelOutboundMessage out = new ChannelOutboundMessage();
        out.setUserId("u1");
        out.setSessionId("s1");
        String topic = String.valueOf(publishTopic.invoke(adapter, out, Map.of("publish_topic", "client/{client_id}/{session_id}/down")));
        if (!"client/u1/s1/down".equals(topic)) {
            throw new AssertionError("mqtt publish topic template mismatch: " + topic);
        }
    }
}
