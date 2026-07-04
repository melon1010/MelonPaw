package com.melon.channels;

import com.melon.core.util.JsonUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

public class MqttChannelAdapter extends BasicChannelAdapter {

    private final Map<String, MqttClient> clients = new ConcurrentHashMap<>();

    public MqttChannelAdapter() {
        super("mqtt", true, false, false, false);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId,
                                                  Map<String, Object> config,
                                                  ChannelInboundDispatcher dispatcher) {
        if (!missing(config).isBlank()) {
            return CompletableFuture.completedFuture(health(agentId, config));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                stop(agentId).join();
                MqttClient client = new MqttClient(serverUri(config), clientId(agentId, config), new MemoryPersistence());
                client.setCallback(callback(agentId, config, dispatcher));
                client.connect(options(config));
                String topic = stringValue(config.get("subscribe_topic"));
                int qos = intValue(config.get("qos"), 0);
                client.subscribe(topic, qos);
                clients.put(key(agentId), client);
                return health(agentId, config);
            } catch (Exception e) {
                return ChannelHealth.of(type(), "failed", false, false, true,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        MqttClient client = clients.remove(key(agentId));
        if (client == null) {
            return CompletableFuture.completedFuture(ChannelHealth.of(type(), "stopped", false, true, true, "Channel stopped"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (client.isConnected()) client.disconnect();
                client.close();
            } catch (MqttException ignored) {
                // best effort
            }
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel stopped");
        });
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        boolean enabled = Boolean.TRUE.equals(config != null ? config.get("enabled") : null);
        if (!enabled) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        String missing = missing(config != null ? config : Map.of());
        if (!missing.isBlank()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + missing);
        }
        MqttClient client = clients.get(key(agentId));
        boolean running = client != null && client.isConnected();
        return ChannelHealth.of(type(), running ? "running" : "disconnected", running, true, true,
                running ? "MQTT client is connected." : "MQTT client is not connected.");
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        MqttClient client = clients.get(key(message.getAgentId()));
        if (client == null || !client.isConnected()) {
            mark(message, "failed", "MQTT client is not connected.");
            return CompletableFuture.completedFuture(message);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String topic = publishTopic(message, config);
                MqttMessage mqttMessage = new MqttMessage(message.getText().getBytes(StandardCharsets.UTF_8));
                mqttMessage.setQos(intValue(config.get("qos"), 0));
                client.publish(topic, mqttMessage);
                mark(message, "sent", topic);
            } catch (Exception e) {
                mark(message, "failed", e.getMessage());
            }
            return message;
        });
    }

    @Override
    public void close() {
        for (String agentId : clients.keySet()) {
            stop(agentId).join();
        }
        clients.clear();
    }

    private MqttCallbackExtended callback(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        return new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
            }

            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                ChannelInboundMessage inbound = inbound(agentId, topic, message);
                dispatcher.dispatch(inbound, intValue(config.get("priority"), 20));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        };
    }

    private ChannelInboundMessage inbound(String agentId, String topic, MqttMessage message) {
        String text = new String(message.getPayload(), StandardCharsets.UTF_8);
        Map<String, Object> json = JsonUtils.fromJson(text, Map.class);
        String userId = json != null ? first(json, "user_id", "userId", "client_id", "from") : "";
        String sessionId = json != null ? first(json, "session_id", "sessionId", "conversation_id", "client_id") : "";
        String content = json != null ? first(json, "content", "text", "message") : text;
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(!userId.isBlank() ? userId : topic);
        inbound.setSessionId(!sessionId.isBlank() ? sessionId : topic);
        inbound.setContent(content);
        Map<String, Object> meta = json != null ? new LinkedHashMap<>(json) : new LinkedHashMap<>();
        meta.put("topic", topic);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("topic", topic, Map.of("topic", topic)));
        return inbound;
    }

    private MqttConnectOptions options(Map<String, Object> config) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(booleanValue(config.get("clean_session"), true));
        String username = stringValue(config.get("username"));
        String password = stringValue(config.get("password"));
        if (!username.isBlank()) options.setUserName(username);
        if (!password.isBlank()) options.setPassword(password.toCharArray());
        options.setAutomaticReconnect(true);
        return options;
    }

    private String publishTopic(ChannelOutboundMessage message, Map<String, Object> config) {
        String topic = message.getTo() != null && message.getTo().getExtra() != null
                ? stringValue(message.getTo().getExtra().get("reply_topic"))
                : "";
        if (topic.isBlank() && message.getTo() != null) topic = stringValue(message.getTo().getId());
        if (topic.isBlank()) topic = stringValue(config.get("publish_topic"));
        return topic.replace("{client_id}", message.getUserId())
                .replace("{session_id}", message.getSessionId());
    }

    private String serverUri(Map<String, Object> config) {
        String transport = stringValue(config.get("transport"), "tcp");
        String scheme = switch (transport) {
            case "websockets", "ws" -> "ws";
            case "wss" -> "wss";
            default -> booleanValue(config.get("tls_enabled"), false) ? "ssl" : "tcp";
        };
        return scheme + "://" + stringValue(config.get("host")) + ":" + intValue(config.get("port"), 1883);
    }

    private String clientId(String agentId, Map<String, Object> config) {
        String configured = stringValue(config.get("client_id"));
        if (!configured.isBlank()) return configured;
        return "melon-" + key(agentId) + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String missing(Map<String, Object> config) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (stringValue(config.get("host")).isBlank()) missing.add("host");
        if (stringValue(config.get("subscribe_topic")).isBlank()) missing.add("subscribe_topic");
        if (stringValue(config.get("publish_topic")).isBlank()) missing.add("publish_topic");
        return String.join(", ", missing);
    }

    private String first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail != null ? detail : "");
        message.setMeta(meta);
    }
}
