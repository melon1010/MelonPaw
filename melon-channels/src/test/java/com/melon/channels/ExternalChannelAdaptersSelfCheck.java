package com.melon.channels;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ExternalChannelAdaptersSelfCheck {

    private ExternalChannelAdaptersSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry();
        for (String type : List.of("qq", "wechat", "wecom", "yuanbao", "xiaoyi", "voice", "sip")) {
            ChannelAdapter adapter = registry.get(type);
            if (!adapter.implemented()) {
                throw new AssertionError(type + " should be backed by a real adapter");
            }
        }

        ChannelAdapter qqAdapter = registry.get("qq");
        ChannelInboundMessage qq = qqAdapter.parseWebhook("default", Map.of(
                "t", "GROUP_AT_MESSAGE_CREATE",
                "d", Map.of("group_openid", "g1", "author", Map.of("member_openid", "u1"), "content", "hi", "id", "m1")
        ), Map.of());
        if (!"qq:group:g1".equals(qq.getSessionId())
                || !"hi".equals(qq.getContent())
                || !"u1".equals(qq.getUserId())
                || !"g1".equals(qq.getReplyTo().getId())
                || !"group:g1".equals(qq.getReplyTo().toHandle())) {
            throw new AssertionError("QQ event parse failed: " + qq.getChannelMeta());
        }
        ChannelOutboundMessage qqOut = new ChannelOutboundMessage();
        qqOut.setAgentId("default");
        qqOut.setChannel("qq");
        qqOut.setUserId(qq.getUserId());
        qqOut.setSessionId(qq.getSessionId());
        qqOut.setTo(qq.getReplyTo());
        qqOut.setText("answer");
        qqOut.setMeta(Map.of("channel_meta", qq.getChannelMeta()));
        Object qqRoute = reflectQqRoute(qqAdapter, qqOut, qq.getChannelMeta());
        if (!"/v2/groups/g1/messages".equals(reflectString(qqRoute, "path"))) {
            throw new AssertionError("QQ group route failed: " + qqRoute);
        }

        ChannelInboundMessage qqC2c = qqAdapter.parseWebhook("default", Map.of(
                "t", "C2C_MESSAGE_CREATE",
                "d", Map.of("author", Map.of("user_openid", "u2"), "content", "hello", "id", "m2")
        ), Map.of());
        ChannelOutboundMessage qqC2cOut = new ChannelOutboundMessage();
        qqC2cOut.setAgentId("default");
        qqC2cOut.setChannel("qq");
        qqC2cOut.setUserId(qqC2c.getUserId());
        qqC2cOut.setSessionId(qqC2c.getSessionId());
        qqC2cOut.setTo(qqC2c.getReplyTo());
        qqC2cOut.setText("answer");
        qqC2cOut.setMeta(Map.of("channel_meta", qqC2c.getChannelMeta()));
        Object c2cRoute = reflectQqRoute(qqAdapter, qqC2cOut, qqC2c.getChannelMeta());
        if (!"/v2/users/u2/messages".equals(reflectString(c2cRoute, "path"))) {
            throw new AssertionError("QQ c2c route failed: " + c2cRoute);
        }

        ChannelInboundMessage wechat = registry.get("wechat").parseWebhook("default", Map.of(
                "from_user_id", "wx_user",
                "context_token", "ctx",
                "item_list", List.of(
                        Map.of("text_item", Map.of("text", "hello")),
                        Map.of("type", 4, "file_item", Map.of(
                                "file_name", "report.pdf",
                                "media", Map.of("encrypt_query_param", "https://cdn.example/report", "aes_key", "0123456789abcdef0123456789abcdef")
                        ))
                )
        ), Map.of());
        if (!"wechat:wx_user".equals(wechat.getSessionId())
                || !"hello".equals(wechat.getContent())
                || wechat.getAttachments().isEmpty()
                || !"report.pdf".equals(wechat.getAttachments().get(0).get("filename"))) {
            throw new AssertionError("WeChat event parse failed: " + wechat.getAttachments());
        }

        ChannelInboundMessage wecom = registry.get("wecom").parseWebhook("default", Map.of(
                "sender_id", "u1",
                "chatid", "c1",
                "chat_type", "group",
                "text", Map.of("content", "question")
        ), Map.of());
        if (!"wecom:group:c1".equals(wecom.getSessionId()) || !"question".equals(wecom.getContent())) {
            throw new AssertionError("WeCom event parse failed");
        }

        ChannelInboundMessage xiaoyi = registry.get("xiaoyi").parseWebhook("default", Map.of(
                "id", "req1",
                "params", Map.of(
                        "id", "task1",
                        "sessionId", "s1",
                        "message", Map.of("parts", List.of(Map.of("text", "ping"))))
        ), Map.of());
        if (!"xiaoyi:s1".equals(xiaoyi.getSessionId()) || !"ping".equals(xiaoyi.getContent())) {
            throw new AssertionError("XiaoYi A2A parse failed");
        }

        ChannelInboundMessage yuanbao = registry.get("yuanbao").parseWebhook("default", Map.of(
                "group_openid", "g2",
                "from_openid", "u2",
                "content", "yo"
        ), Map.of());
        if (!"yuanbao:group:g2".equals(yuanbao.getSessionId()) || !"yo".equals(yuanbao.getContent())) {
            throw new AssertionError("Yuanbao parse failed");
        }
        YuanbaoProto.ConnMsg auth = YuanbaoProto.decodeConn(YuanbaoProto.authBind("bot1", "bot", "token1"));
        if (!"auth-bind".equals(auth.cmd()) || !"conn_access".equals(auth.module()) || auth.data().length == 0) {
            throw new AssertionError("Yuanbao AuthBind protobuf failed: " + auth);
        }
        YuanbaoProto.ConnMsg c2c = YuanbaoProto.decodeConn(YuanbaoProto.sendC2C("u1", "bot1", "hello"));
        if (!"send_c2c_message".equals(c2c.cmd()) || !"yuanbao_openclaw_proxy".equals(c2c.module()) || c2c.data().length == 0) {
            throw new AssertionError("Yuanbao send protobuf failed: " + c2c);
        }
        if (ChannelHttpSupport.qrPngBase64("https://example.com/login").length() < 100) {
            throw new AssertionError("QR PNG generation failed");
        }

        AtomicReference<String> posted = new AtomicReference<>("");
        AtomicReference<String> wechatAuthType = new AtomicReference<>("");
        AtomicReference<String> wechatUin = new AtomicReference<>("");
        AtomicReference<String> wechatSend = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/get_bot_qrcode", exchange -> {
            wechatAuthType.set(exchange.getRequestHeaders().getFirst("AuthorizationType"));
            wechatUin.set(exchange.getRequestHeaders().getFirst("X-WECHAT-UIN"));
            byte[] ok = ("{\"qrcode\":\"wx-token-1\","
                    + "\"qrcode_img_content\":\"https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=wx-token-1&bot_type=3\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.createContext("/ilink/bot/sendmessage", exchange -> {
            wechatSend.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"ret\":0,\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.createContext("/ilink/bot/get_qrcode_status", exchange -> {
            byte[] ok = "{\"status\":\"confirmed\",\"bot_token\":\"token-from-qr\",\"baseurl\":\"http://127.0.0.1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.createContext("/reply", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, Object> qr = registry.get("wechat").qrcode("default", Map.of("base_url", baseUrl));
            String qrImage = String.valueOf(qr.get("qrcode_img"));
            if (qrImage.startsWith("http") || qrImage.startsWith("data:image") || qrImage.length() < 100) {
                throw new AssertionError("WeChat qrcode_img must be plain PNG base64 for the frontend: " + qr);
            }
            byte[] png = Base64.getDecoder().decode(qrImage);
            if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 0x50 || png[2] != 0x4e || png[3] != 0x47) {
                throw new AssertionError("WeChat qrcode_img is not a PNG base64 payload");
            }
            if (!"wx-token-1".equals(qr.get("poll_token"))) {
                throw new AssertionError("WeChat poll token mismatch: " + qr);
            }
            if (!"ilink_bot_token".equals(wechatAuthType.get()) || wechatUin.get() == null || wechatUin.get().isBlank()) {
                throw new AssertionError("WeChat QR request missed iLink headers");
            }
            Path tokenFile = Files.createTempFile("wechat-token", ".txt");
            Map<String, Object> qrStatus = registry.get("wechat").qrcodeStatus("default",
                    Map.of("base_url", baseUrl, "bot_token_file", tokenFile.toString()), "wx-token-1");
            if (!"success".equals(qrStatus.get("status"))
                    || !"token-from-qr".equals(((Map<?, ?>) qrStatus.get("credentials")).get("bot_token"))
                    || !Files.readString(tokenFile).contains("token-from-qr")) {
                throw new AssertionError("WeChat QR status should activate persisted credentials: " + qrStatus);
            }

            ChannelOutboundMessage wxOut = new ChannelOutboundMessage();
            wxOut.setAgentId("default");
            wxOut.setChannel("wechat");
            wxOut.setUserId("wx_user");
            wxOut.setSessionId("wechat:wx_user");
            wxOut.setTo(wechat.getReplyTo());
            wxOut.setText("answer");
            wxOut.setMeta(Map.of("channel_meta", Map.of("wechat_context_token", "ctx")));
            registry.get("wechat").send(wxOut, Map.of("enabled", true, "bot_token", "token1", "base_url", baseUrl)).get();
            if (!wechatSend.get().contains("\"context_token\":\"ctx\"")
                    || !"sent".equals(wxOut.getMeta().get("delivery_status"))) {
                throw new AssertionError("WeChat outbound failed: " + wxOut.getMeta() + " body=" + wechatSend.get());
            }

            VoiceChannelAdapter voice = (VoiceChannelAdapter) registry.get("voice");
            String twiml = voice.twiml("你好", Map.of("language", "zh-CN"));
            if (!twiml.contains("<Response>") || !twiml.contains("你好")) {
                throw new AssertionError("Voice TwiML failed: " + twiml);
            }

            String url = baseUrl + "/reply";
            ChannelOutboundMessage out = new ChannelOutboundMessage();
            out.setAgentId("default");
            out.setChannel("wecom");
            out.setUserId("u1");
            out.setSessionId("wecom:group:c1");
            out.setTo(wecom.getReplyTo());
            out.setText("answer");
            out.setMeta(Map.of("channel_meta", Map.of("reply_url", url, "wecom_chatid", "c1")));
            registry.get("wecom").send(out, Map.of("enabled", true, "bot_id", "b", "secret", "s")).get();
            if (!posted.get().contains("answer") || !"sent".equals(out.getMeta().get("delivery_status"))) {
                throw new AssertionError("WeCom outbound failed: " + out.getMeta());
            }
        } finally {
            server.stop(0);
            registry.close();
        }
    }

    private static Object reflectQqRoute(ChannelAdapter adapter,
                                         ChannelOutboundMessage message,
                                         Map<String, Object> meta) throws Exception {
        Method method = adapter.getClass().getDeclaredMethod("resolveRoute", ChannelOutboundMessage.class, Map.class);
        method.setAccessible(true);
        return method.invoke(adapter, message, meta);
    }

    private static String reflectString(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return String.valueOf(method.invoke(target));
    }
}
