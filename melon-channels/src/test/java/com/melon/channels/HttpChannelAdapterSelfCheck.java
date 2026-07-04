package com.melon.channels;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpChannelAdapterSelfCheck {

    private HttpChannelAdapterSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        HttpChannelAdapter telegram = new HttpChannelAdapter("telegram", false, true, true);
        ChannelInboundMessage telegramMessage = telegram.parseWebhook("default", Map.of(
                "message", Map.of(
                        "chat", Map.of("id", 12345),
                        "from", Map.of("id", 67890),
                        "text", "/hello",
                        "entities", java.util.List.of(Map.of("type", "bot_command"))
                )
        ), Map.of());
        if (!"12345".equals(telegramMessage.getSessionId())
                || !"67890".equals(telegramMessage.getUserId())
                || !"/hello".equals(telegramMessage.getContent())
                || !Boolean.TRUE.equals(telegramMessage.getChannelMeta().get("has_bot_command"))) {
            throw new AssertionError("telegram payload was not parsed: " + telegramMessage.getChannelMeta());
        }

        HttpChannelAdapter feishu = new HttpChannelAdapter("feishu", false, true, true);
        ChannelHealth badFeishu = feishu.health("default", Map.of("enabled", true));
        if (!"misconfigured".equals(badFeishu.getStatus()) || badFeishu.isAvailable()) {
            throw new AssertionError("feishu without app credentials should be misconfigured");
        }
        ChannelInboundMessage feishuMessage = feishu.parseWebhook("default", Map.of(
                "app_id", "cli_123456",
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_x")),
                        "message", Map.of("chat_id", "oc_1234567890", "chat_type", "group",
                                "content", "{\"text\":\"hi @_all\"}")
                )
        ), Map.of());
        if (!"3456_34567890".equals(feishuMessage.getSessionId())
                || !"hi @_all".equals(feishuMessage.getContent())
                || !Boolean.TRUE.equals(feishuMessage.getChannelMeta().get("bot_mentioned"))
                || !"chat_id".equals(feishuMessage.getChannelMeta().get("feishu_receive_id_type"))) {
            throw new AssertionError("feishu payload was not parsed: " + feishuMessage.getChannelMeta());
        }

        HttpChannelAdapter slack = new HttpChannelAdapter("slack", false, true, true);
        ChannelInboundMessage slackMessage = slack.parseWebhook("default", Map.of(
                "event", Map.of(
                        "channel", "C1",
                        "user", "U1",
                        "ts", "1.0",
                        "text", "/bot <@B1> with file",
                        "files", java.util.List.of(Map.of(
                                "name", "a.png",
                                "mimetype", "image/png",
                                "url_private_download", "https://files.slack.com/a.png"
                        ))
                ),
                "bot_prefix", "/bot",
                "bot_user_id", "B1"
        ), Map.of());
        if (slackMessage.getAttachments().size() != 1
                || !"https://files.slack.com/a.png".equals(slackMessage.getAttachments().get(0).get("url_private_download"))
                || !Boolean.TRUE.equals(slackMessage.getChannelMeta().get("bot_mentioned"))
                || !"slack:ch:C1".equals(slackMessage.getSessionId())) {
            throw new AssertionError("slack payload was not parsed: " + slackMessage.getChannelMeta());
        }

        HttpChannelAdapter discord = new HttpChannelAdapter("discord", false, true, true);
        ChannelInboundMessage discordMessage = discord.parseWebhook("default", Map.of(
                "channel_id", "D1",
                "author", Map.of("id", "DU1"),
                "content", "with attachment",
                "attachments", java.util.List.of(Map.of(
                        "filename", "report.pdf",
                        "content_type", "application/pdf",
                        "url", "https://cdn.discordapp.com/report.pdf"
                ))
        ), Map.of());
        if (discordMessage.getAttachments().size() != 1
                || !"report.pdf".equals(discordMessage.getAttachments().get(0).get("filename"))) {
            throw new AssertionError("discord attachment was not parsed: " + discordMessage.getAttachments());
        }

        HttpChannelAdapter matrix = new HttpChannelAdapter("matrix", false, false, true);
        ChannelInboundMessage matrixMessage = matrix.parseWebhook("default", Map.of(
                "room_id", "!r:server",
                "sender", "@u:server",
                "content", Map.of(
                        "body", "photo.jpg",
                        "url", "mxc://server/media",
                        "info", Map.of("mimetype", "image/jpeg")
                )
        ), Map.of());
        if (matrixMessage.getAttachments().size() != 1
                || !"image/jpeg".equals(matrixMessage.getAttachments().get(0).get("mime_type"))) {
            throw new AssertionError("matrix media attachment was not parsed: " + matrixMessage.getAttachments());
        }

        AtomicReference<String> posted = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reply", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String reply = "http://127.0.0.1:" + port + "/reply";
            HttpChannelAdapter dingtalk = new HttpChannelAdapter("dingtalk", false, true, true);
            ChannelInboundMessage inbound = dingtalk.parseWebhook("default", Map.of(
                    "sessionWebhook", reply,
                    "conversationId", "cid_1234567890",
                    "senderStaffId", "uid",
                    "senderNick", "alice",
                    "conversationType", "2",
                    "text", Map.of("content", "question")
            ), Map.of());
            if (!"34567890".equals(inbound.getSessionId())
                    || !"alice#uid".equals(inbound.getUserId())
                    || !"group".equals(inbound.getChannelMeta().get("conversation_type"))) {
                throw new AssertionError("dingtalk inbound shape drifted: " + inbound.getChannelMeta());
            }
            ChannelOutboundMessage out = new ChannelOutboundMessage();
            out.setAgentId("default");
            out.setChannel("dingtalk");
            out.setUserId(inbound.getUserId());
            out.setSessionId(inbound.getSessionId());
            out.setTo(inbound.getReplyTo());
            out.setText("answer");
            out.setMeta(Map.of("channel_meta", inbound.getChannelMeta()));
            dingtalk.send(out, Map.of("enabled", true)).get();
            if (!posted.get().contains("answer") || !"sent".equals(out.getMeta().get("delivery_status"))) {
                throw new AssertionError("dingtalk sessionWebhook was not posted: " + out.getMeta());
            }
        } finally {
            server.stop(0);
        }
    }
}
