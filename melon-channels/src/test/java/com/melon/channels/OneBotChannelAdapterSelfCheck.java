package com.melon.channels;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class OneBotChannelAdapterSelfCheck {

    private OneBotChannelAdapterSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        OneBotChannelAdapter adapter = new OneBotChannelAdapter();
        ChannelInboundMessage group = adapter.parseWebhook("default", Map.of(
                "post_type", "message",
                "message_type", "group",
                "group_id", 10001,
                "user_id", 20002,
                "raw_message", "hello"
        ), Map.of());
        if (!"group:10001".equals(group.getSessionId())
                || !"20002".equals(group.getUserId())
                || !"hello".equals(group.getContent())) {
            throw new AssertionError("onebot group payload was not parsed");
        }

        AtomicReference<String> sent = new AtomicReference<>("");
        adapter.attach("default", new OneBotChannelAdapter.OneBotConnection() {
            @Override
            public CompletableFuture<Void> send(String payload) {
                sent.set(payload);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String id() {
                return "check";
            }
        });
        ChannelOutboundMessage out = new ChannelOutboundMessage();
        out.setAgentId("default");
        out.setChannel("onebot");
        out.setUserId(group.getUserId());
        out.setSessionId(group.getSessionId());
        out.setTo(group.getReplyTo());
        out.setText("answer");
        adapter.send(out, Map.of("enabled", true)).get();
        if (!sent.get().contains("send_group_msg")
                || !sent.get().contains("10001")
                || !"sent".equals(out.getMeta().get("delivery_status"))) {
            throw new AssertionError("onebot group response was not sent: " + sent.get());
        }

        adapter.detach("default", "check");
        ChannelOutboundMessage failed = new ChannelOutboundMessage();
        failed.setAgentId("default");
        failed.setChannel("onebot");
        failed.setText("no connection");
        adapter.send(failed, Map.of("enabled", true)).get();
        if (!"failed".equals(failed.getMeta().get("delivery_status"))) {
            throw new AssertionError("missing connection should fail delivery");
        }
    }
}
