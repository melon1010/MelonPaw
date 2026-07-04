package com.melon.channels;

import com.melon.core.util.JsonUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

final class ChannelHttpSupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+|www\\.\\S+", Pattern.CASE_INSENSITIVE);

    private ChannelHttpSupport() {
    }

    static HttpResponse<String> get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .GET();
        if (headers != null) headers.forEach(builder::header);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String url, Map<String, String> headers, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)));
        if (headers != null) headers.forEach(builder::header);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, Object> json(String text) {
        Map<String, Object> map = JsonUtils.fromJson(text, Map.class);
        return map != null ? map : Map.of();
    }

    static Map<String, Object> copy(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw != null) raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    static Map<?, ?> map(Object raw) {
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    static List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    static String first(Map<?, ?> map, String... keys) {
        if (map == null) return "";
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static List<String> missing(Map<String, Object> config, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (stringValue(config.get(key)).isBlank()) result.add(key);
        }
        return result;
    }

    static String target(ChannelOutboundMessage message, String... keys) {
        String id = message.getTo() != null ? stringValue(message.getTo().getId()) : "";
        if (!id.isBlank() && !"default".equals(id)) return id;
        Map<?, ?> extra = message.getTo() != null ? message.getTo().getExtra() : Map.of();
        String value = first(extra, keys);
        if (!value.isBlank()) return value;
        Map<?, ?> meta = map(message.getMeta().get("channel_meta"));
        value = first(meta, keys);
        if (!value.isBlank()) return value;
        return message.getUserId();
    }

    static void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail);
        message.setMeta(meta);
    }

    static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String hmacSha256Hex(String key, String data) {
        byte[] bytes = hmacSha256(key, data);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static String hmacSha256Base64(String key, String data) {
        return Base64.getEncoder().encodeToString(hmacSha256(key, data));
    }

    private static byte[] hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte b : data) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static String beijingTimestamp() {
        return ZonedDateTime.now(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    static String sanitizeQqText(String text) {
        return URL_PATTERN.matcher(text != null ? text : "").replaceAll("[链接已省略]");
    }

    static String qrPngBase64(String text) {
        try {
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240);
            var image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("QR code generation failed", e);
        }
    }

    static String aesGcmDecryptBase64(String encryptedBase64, String keyBase64) {
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            byte[] raw = Base64.getDecoder().decode(encryptedBase64);
            if (raw.length < 28) throw new IllegalArgumentException("Ciphertext too short");
            byte[] iv = java.util.Arrays.copyOfRange(raw, 0, 12);
            byte[] cipherText = java.util.Arrays.copyOfRange(raw, 12, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

final class YuanbaoProto {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final SecureRandom RANDOM = new SecureRandom();

    static byte[] authBind(String botId, String source, String token) {
        byte[] authInfo = msg(
                str(1, botId),
                str(2, source),
                str(3, token));
        byte[] deviceInfo = msg(str(10, "16"));
        byte[] authReq = msg(
                str(1, "ybBot"),
                bytes(2, authInfo),
                bytes(3, deviceInfo));
        return conn(0, "auth-bind", "conn_access", false, authReq);
    }

    static byte[] ping() {
        return conn(0, "ping", "conn_access", false, new byte[0]);
    }

    static byte[] pushAck(ConnMsg original) {
        byte[] head = msg(
                uint32(1, 3),
                str(2, original.cmd()),
                uint32(3, nextSeq()),
                str(4, original.msgId()),
                str(5, original.module()));
        return msg(bytes(1, head));
    }

    static byte[] sendC2C(String toAccount, String fromAccount, String text) {
        byte[] body = msg(
                str(1, "TIMTextElem"),
                bytes(2, msg(str(1, text))));
        byte[] req = msg(
                str(2, toAccount),
                str(3, fromAccount),
                uint32(4, RANDOM.nextInt(Integer.MAX_VALUE)),
                bytes(5, body));
        return conn(0, "send_c2c_message", "yuanbao_openclaw_proxy", false, req);
    }

    static byte[] sendGroup(String groupCode, String fromAccount, String text) {
        byte[] body = msg(
                str(1, "TIMTextElem"),
                bytes(2, msg(str(1, text))));
        byte[] req = msg(
                str(2, groupCode),
                str(3, fromAccount),
                str(5, String.valueOf(RANDOM.nextInt(Integer.MAX_VALUE))),
                bytes(6, body));
        return conn(0, "send_group_message", "yuanbao_openclaw_proxy", false, req);
    }

    static ConnMsg decodeConn(byte[] raw) {
        Map<Integer, List<byte[]>> conn = fields(raw);
        byte[] headBytes = firstBytes(conn, 1);
        Map<Integer, List<byte[]>> head = fields(headBytes);
        return new ConnMsg(
                intValue(firstVarint(head, 1)),
                string(firstBytes(head, 2)),
                longValue(firstVarint(head, 3)),
                string(firstBytes(head, 4)),
                string(firstBytes(head, 5)),
                firstVarint(head, 6) != 0,
                intValue(firstVarint(head, 10)),
                firstBytes(conn, 2));
    }

    private static byte[] conn(int cmdType, String cmd, String module, boolean needAck, byte[] data) {
        byte[] head = msg(
                uint32(1, cmdType),
                str(2, cmd),
                uint32(3, nextSeq()),
                str(4, UUID.randomUUID().toString().replace("-", "")),
                str(5, module),
                needAck ? bool(6, true) : new byte[0]);
        return data != null && data.length > 0 ? msg(bytes(1, head), bytes(2, data)) : msg(bytes(1, head));
    }

    private static int nextSeq() {
        int value = SEQ.incrementAndGet();
        if (value == Integer.MAX_VALUE) SEQ.set(0);
        return value;
    }

    private static byte[] msg(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            if (field != null && field.length > 0) out.writeBytes(field);
        }
        return out.toByteArray();
    }

    private static byte[] str(int field, String value) {
        if (value == null || value.isBlank()) return new byte[0];
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(int field, byte[] value) {
        if (value == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] uint32(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
        return out.toByteArray();
    }

    private static byte[] bool(int field, boolean value) {
        return uint32(field, value ? 1 : 0);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static Map<Integer, List<byte[]>> fields(byte[] raw) {
        Map<Integer, List<byte[]>> result = new LinkedHashMap<>();
        if (raw == null) return result;
        int[] pos = {0};
        while (pos[0] < raw.length) {
            long tag = readVarint(raw, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long value = readVarint(raw, pos);
                result.computeIfAbsent(field, ignored -> new ArrayList<>()).add(varintBytes(value));
            } else if (wire == 2) {
                int len = (int) readVarint(raw, pos);
                if (len < 0 || pos[0] + len > raw.length) break;
                byte[] value = java.util.Arrays.copyOfRange(raw, pos[0], pos[0] + len);
                pos[0] += len;
                result.computeIfAbsent(field, ignored -> new ArrayList<>()).add(value);
            } else {
                break;
            }
        }
        return result;
    }

    private static long firstVarint(Map<Integer, List<byte[]>> fields, int field) {
        byte[] raw = firstBytes(fields, field);
        return readVarint(raw, new int[]{0});
    }

    private static byte[] firstBytes(Map<Integer, List<byte[]>> fields, int field) {
        List<byte[]> values = fields.get(field);
        return values == null || values.isEmpty() ? new byte[0] : values.get(0);
    }

    private static byte[] varintBytes(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, value);
        return out.toByteArray();
    }

    private static long readVarint(byte[] raw, int[] pos) {
        if (raw == null) return 0;
        long value = 0;
        int shift = 0;
        while (pos[0] < raw.length && shift < 64) {
            int b = raw[pos[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return value;
    }

    private static String string(byte[] raw) {
        return raw == null || raw.length == 0 ? "" : new String(raw, StandardCharsets.UTF_8);
    }

    private static int intValue(long value) {
        return (int) value;
    }

    private static long longValue(long value) {
        return value;
    }

    record ConnMsg(int cmdType, String cmd, long seqNo, String msgId, String module,
                   boolean needAck, int status, byte[] data) {
    }
}

class WeChatChannelAdapter extends BasicChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeChatChannelAdapter.class);
    private static final String DEFAULT_BASE = "https://ilinkai.weixin.qq.com";
    private static final String VERSION = "2.0.1";
    private static final int PROCESSED_LIMIT = 2000;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> cursors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> processed = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> contextTokens = new ConcurrentHashMap<>();
    private final Map<String, String> tokenFiles = new ConcurrentHashMap<>();

    WeChatChannelAdapter() {
        super("wechat", true, true, false, false);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        Map<String, Object> runtime = withResolvedToken(config);
        List<String> missing = ChannelHttpSupport.missing(runtime, "bot_token");
        if (!Boolean.TRUE.equals(runtime.get("enabled")) || !missing.isEmpty()) {
            log.info("WeChat channel not started: agent={}, enabled={}, missing={}",
                    agentId, runtime.get("enabled"), missing);
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        super.start(agentId, runtime);
        tokenFiles.put(key(agentId), tokenFilePath(runtime));
        loadContextTokens(agentId, runtime);
        String key = key(agentId);
        tasks.computeIfAbsent(key, ignored -> executor.scheduleWithFixedDelay(
                () -> poll(agentId, new LinkedHashMap<>(runtime), dispatcher), 0, 1, TimeUnit.SECONDS));
        log.info("WeChat channel started: agent={}, base={}, token={}...",
                agentId, base(runtime), stringValue(runtime.get("bot_token")).substring(0, Math.min(12, stringValue(runtime.get("bot_token")).length())));
        return CompletableFuture.completedFuture(health(agentId, runtime));
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        ScheduledFuture<?> task = tasks.remove(key(agentId));
        if (task != null) task.cancel(true);
        return super.stop(agentId);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        Map<String, Object> runtime = withResolvedToken(config);
        if (!Boolean.TRUE.equals(runtime.get("enabled"))) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        List<String> missing = ChannelHttpSupport.missing(runtime, "bot_token");
        if (!missing.isEmpty()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: bot_token");
        }
        ScheduledFuture<?> task = tasks.get(key(agentId));
        boolean running = task != null && !task.isCancelled() && !task.isDone();
        return ChannelHealth.of(type(), running ? "running" : "configured", running, true, true,
                running ? "WeChat long-poll is running." : "WeChat is configured but long-poll is not running.");
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        try {
            String base = base(config);
            HttpResponse<String> response = ChannelHttpSupport.get(base + "/ilink/bot/get_bot_qrcode?bot_type=3", headers(Map.of()));
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            String qrcode = stringValue(body.get("qrcode"));
            String qrcodeContent = stringValue(body.get("qrcode_img_content"));
            if (qrcode.isBlank() && qrcodeContent.isBlank()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false,
                        "status", "failed", "detail", "WeChat returned empty QR code data: " + response.body());
            }
            String scanUrl = qrcodeContent.startsWith("http")
                    ? qrcodeContent
                    : "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=" + ChannelHttpSupport.encode(qrcode) + "&bot_type=3";
            return Map.of(
                    "qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl),
                    "poll_token", qrcode,
                    "enabled", true,
                    "status", response.statusCode() >= 200 && response.statusCode() < 300 ? "waiting" : "failed",
                    "detail", response.body()
            );
        } catch (Exception e) {
            return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        try {
            String base = base(config);
            HttpResponse<String> response = ChannelHttpSupport.get(base + "/ilink/bot/get_qrcode_status?qrcode=" + ChannelHttpSupport.encode(token), headers(Map.of()));
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            Map<String, Object> credentials = new LinkedHashMap<>();
            credentials.put("bot_token", body.getOrDefault("bot_token", ""));
            credentials.put("base_url", body.getOrDefault("baseurl", base));
            persistBotTokenIfConfigured(config, stringValue(credentials.get("bot_token")));
            String status = stringValue(body.get("status"), "unknown");
            String frontendStatus = ("confirmed".equals(status) || "success".equals(status)) && !stringValue(credentials.get("bot_token")).isBlank()
                    ? "success"
                    : status;
            return Map.of("status", frontendStatus, "credentials", credentials, "detail", body);
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of(), "detail", e.getMessage());
        }
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        return inbound(agentId, body, headers);
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String toUser = ChannelHttpSupport.target(message, "to_user_id", "from_user_id", "user_id");
                Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
                String contextToken = ChannelHttpSupport.first(meta, "context_token", "wechat_context_token");
                if (contextToken.isBlank() && message.getTo() != null) {
                    contextToken = ChannelHttpSupport.first(message.getTo().getExtra(), "context_token", "wechat_context_token");
                }
                if (contextToken.isBlank()) {
                    contextToken = contextTokens.getOrDefault(key(message.getAgentId()), Map.of())
                            .getOrDefault(toUser, "");
                }
                if (contextToken.isBlank()) {
                    log.warn("WeChat send without context_token: agent={}, user={}, session={}",
                            message.getAgentId(), message.getUserId(), message.getSessionId());
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("from_user_id", "");
                msg.put("to_user_id", toUser);
                msg.put("client_id", UUID.randomUUID().toString());
                msg.put("message_type", 2);
                msg.put("message_state", 2);
                msg.put("context_token", contextToken);
                msg.put("item_list", List.of(Map.of("type", 1, "text_item", Map.of("text", message.getText()))));
                HttpResponse<String> response = ChannelHttpSupport.post(base(config) + "/ilink/bot/sendmessage",
                        headers(config), Map.of("msg", msg, "base_info", Map.of("channel_version", VERSION)));
                boolean sent = ok(response) && accepted(response.body());
                ChannelHttpSupport.mark(message, sent ? "sent" : "failed", response.statusCode() + " " + response.body());
                if (!sent) {
                    log.warn("WeChat send failed: agent={}, to={}, session={}, detail={}",
                            message.getAgentId(), toUser, message.getSessionId(), response.statusCode() + " " + response.body());
                } else {
                    log.info("WeChat outbound sent: agent={}, to={}, session={}, text_len={}",
                            message.getAgentId(), toUser, message.getSessionId(), message.getText().length());
                }
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
                log.warn("WeChat send exception: agent={}, user={}, session={}, error={}",
                        message.getAgentId(), message.getUserId(), message.getSessionId(), e.toString());
            }
            return message;
        });
    }

    private void poll(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        try {
            String cursor = cursors.getOrDefault(key(agentId), "");
            Map<String, Object> body = Map.of(
                    "get_updates_buf", cursor,
                    "base_info", Map.of("channel_version", VERSION));
            HttpResponse<String> response = ChannelHttpSupport.post(base(config) + "/ilink/bot/getupdates", headers(config), body);
            Map<String, Object> data = ChannelHttpSupport.json(response.body());
            int ret = intValue(data.get("ret"));
            String next = stringValue(data.get("get_updates_buf"));
            if (!next.isBlank()) cursors.put(key(agentId), next);
            List<?> messages = ChannelHttpSupport.list(data.get("msgs"));
            if (ret != 0 && !messages.isEmpty()) {
                log.warn("WeChat getupdates returned ret={} with {} message(s): {}", ret, messages.size(), response.body());
            } else if (ret != 0 && ret != -1) {
                log.warn("WeChat getupdates returned ret={}: {}", ret, response.body());
            }
            for (Object item : messages) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> payload = ChannelHttpSupport.copy(raw);
                    if (intValue(payload.get("message_type")) != 1) {
                        log.debug("WeChat skipped non-user message: type={}, context={}",
                                payload.get("message_type"), stringValue(payload.get("context_token")));
                        continue;
                    }
                    if (duplicate(agentId, payload)) {
                        log.debug("WeChat skipped duplicate message: agent={}, context={}, msg={}",
                                agentId, stringValue(payload.get("context_token")), stringValue(payload.get("msg_id")));
                        continue;
                    }
                    ChannelInboundMessage inbound = inbound(agentId, payload, Map.of());
                    if (inbound.getContent().isBlank() && (inbound.getAttachments() == null || inbound.getAttachments().isEmpty())) {
                        log.debug("WeChat skipped empty inbound message: agent={}, user={}, context={}",
                                agentId, inbound.getUserId(), stringValue(payload.get("context_token")));
                        continue;
                    }
                    log.info("WeChat inbound message: agent={}, user={}, session={}, text_len={}",
                            agentId, inbound.getUserId(), inbound.getSessionId(), inbound.getContent().length());
                    dispatcher.dispatch(inbound, 20);
                }
            }
        } catch (Exception e) {
            log.warn("WeChat poll failed: agent={}, base={}, error={}", agentId, base(config), e.toString());
            ChannelHttpSupport.sleep(1500);
        }
    }

    private ChannelInboundMessage inbound(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        String from = ChannelHttpSupport.first(payload, "from_user_id", "sender_id", "user_id");
        String to = ChannelHttpSupport.first(payload, "to_user_id");
        String contextToken = ChannelHttpSupport.first(payload, "context_token");
        String group = ChannelHttpSupport.first(payload, "group_id", "wechat_group_id");
        List<String> texts = new ArrayList<>();
        List<Map<String, Object>> attachments = new ArrayList<>();
        String direct = ChannelHttpSupport.first(payload, "text", "content", "message");
        if (!direct.isBlank()) texts.add(direct);
        for (Object item : ChannelHttpSupport.list(payload.get("item_list"))) {
            Map<?, ?> part = ChannelHttpSupport.map(item);
            int itemType = part.containsKey("type") ? intValue(part.get("type"))
                    : !ChannelHttpSupport.map(part.get("text_item")).isEmpty() ? 1 : 0;
            if (itemType == 1 || itemType == 0) {
                String value = stringValue(ChannelHttpSupport.map(part.get("text_item")).get("text")).trim();
                if (!value.isBlank() && !looksLikeFileName(value)) texts.add(value);
            } else {
                Map<String, Object> attachment = wechatAttachment(part, itemType);
                if (!attachment.isEmpty()) attachments.add(attachment);
            }
        }
        String text = String.join("\n", texts).trim();
        Map<String, Object> meta = new LinkedHashMap<>(payload);
        meta.put("wechat_from_user_id", from);
        meta.put("wechat_to_user_id", to);
        meta.put("wechat_context_token", contextToken);
        meta.put("wechat_group_id", group);
        meta.put("is_group", !group.isBlank());
        if (from != null && !from.isBlank() && contextToken != null && !contextToken.isBlank()) {
            contextTokens.computeIfAbsent(key(agentId), ignored -> new ConcurrentHashMap<>()).put(from, contextToken);
            saveContextTokens(agentId, Map.of("bot_token_file", tokenFiles.getOrDefault(key(agentId), "")), contextTokens.get(key(agentId)));
        }
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(from);
        inbound.setSessionId(!group.isBlank() ? "wechat:group:" + group : "wechat:" + from);
        inbound.setContent(text);
        inbound.setAttachments(attachments);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("dm", from, Map.of(
                "to_user_id", from,
                "context_token", contextToken,
                "wechat_context_token", contextToken,
                "wechat_group_id", group,
                "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private Map<String, Object> wechatAttachment(Map<?, ?> part, int itemType) {
        Map<String, Object> result = new LinkedHashMap<>();
        String mediaType = switch (itemType) {
            case 2 -> "image/jpeg";
            case 3 -> "audio/*";
            case 4 -> "application/octet-stream";
            case 5 -> "video/mp4";
            default -> "";
        };
        String name = switch (itemType) {
            case 2 -> "wechat-image.jpg";
            case 3 -> "wechat-voice.amr";
            case 5 -> "wechat-video.mp4";
            default -> "wechat-file";
        };
        Map<?, ?> item = switch (itemType) {
            case 2 -> ChannelHttpSupport.map(part.get("image_item"));
            case 3 -> ChannelHttpSupport.map(part.get("voice_item"));
            case 4 -> ChannelHttpSupport.map(part.get("file_item"));
            case 5 -> ChannelHttpSupport.map(part.get("video_item"));
            default -> Map.of();
        };
        if (itemType == 3) {
            String asr = stringValue(ChannelHttpSupport.map(item.get("text_item")).get("text"), stringValue(item.get("text")));
            if (!asr.isBlank()) {
                result.put("type", "data");
                result.put("name", "wechat-voice.txt");
                result.put("text", asr);
            }
        }
        if (itemType == 4) {
            name = stringValue(item.get("file_name"), name);
        }
        Map<?, ?> media = ChannelHttpSupport.map(item.get("media"));
        String encrypt = ChannelHttpSupport.first(media, "encrypt_query_param");
        String aesKey = ChannelHttpSupport.first(item, "aeskey");
        if (aesKey.isBlank()) aesKey = ChannelHttpSupport.first(media, "aes_key");
        result.put("type", "data");
        result.put("name", name);
        result.put("filename", name);
        result.put("media_type", mediaType);
        result.put("encrypt_query_param", encrypt);
        result.put("aes_key", aesKey);
        if (!encrypt.isBlank()) result.put("url", encrypt);
        result.put("source", Map.of("type", "url", "url", encrypt, "media_type", mediaType));
        return result;
    }

    private Map<String, String> headers(Map<String, Object> config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AuthorizationType", "ilink_bot_token");
        headers.put("X-WECHAT-UIN", Base64.getEncoder().encodeToString(
                String.valueOf(randomUint32()).getBytes(StandardCharsets.UTF_8)));
        String token = stringValue(config.get("bot_token"));
        if (!token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private Map<String, Object> withResolvedToken(Map<String, Object> config) {
        Map<String, Object> copy = new LinkedHashMap<>(config != null ? config : Map.of());
        if (!stringValue(copy.get("bot_token")).isBlank()) return copy;
        String tokenFile = tokenFilePath(copy);
        if (tokenFile.isBlank()) return copy;
        try {
            Path path = expandUser(tokenFile);
            if (Files.isRegularFile(path)) {
                String token = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!token.isBlank()) {
                    copy.put("bot_token", token);
                    log.info("WeChat loaded bot_token from file: {}", path);
                }
            }
        } catch (Exception e) {
            log.warn("WeChat failed to read bot_token_file {}: {}", tokenFile, e.toString());
        }
        return copy;
    }

    private void persistBotTokenIfConfigured(Map<String, Object> config, String token) {
        String file = tokenFilePath(config);
        if (file.isBlank() || token.isBlank()) return;
        try {
            Path path = expandUser(file);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, token, StandardCharsets.UTF_8);
            log.info("WeChat persisted bot_token to file: {}", path);
        } catch (Exception e) {
            log.warn("WeChat failed to persist bot_token_file {}: {}", file, e.toString());
        }
    }

    private void loadContextTokens(String agentId, Map<String, Object> config) {
        try {
            Path path = contextTokensPath(config);
            if (!Files.isRegularFile(path)) return;
            Map<String, Object> raw = ChannelHttpSupport.json(Files.readString(path, StandardCharsets.UTF_8));
            Map<String, String> tokens = new ConcurrentHashMap<>();
            raw.forEach((k, v) -> {
                String value = stringValue(v);
                if (!value.isBlank()) tokens.put(k, value);
            });
            contextTokens.put(key(agentId), tokens);
            log.info("WeChat loaded {} context_token(s) from {}", tokens.size(), path);
        } catch (Exception e) {
            log.warn("WeChat failed to load context tokens: {}", e.toString());
        }
    }

    private void saveContextTokens(String agentId, Map<String, Object> config, Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
        try {
            Path path = contextTokensPath(config);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, JsonUtils.toJson(tokens), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("WeChat failed to save context tokens for agent {}: {}", agentId, e.toString());
        }
    }

    private Path contextTokensPath(Map<String, Object> config) {
        String tokenFile = tokenFilePath(config);
        Path base = tokenFile.isBlank()
                ? Path.of(System.getProperty("user.home"), ".melonAI", "wechat_bot_token")
                : expandUser(tokenFile);
        return base.getParent().resolve("wechat_context_tokens.json");
    }

    private String tokenFilePath(Map<String, Object> config) {
        return stringValue(config.get("bot_token_file"),
                Path.of(System.getProperty("user.home"), ".melonAI", "wechat_bot_token").toString());
    }

    private Path expandUser(String value) {
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }

    private long randomUint32() {
        return new SecureRandom().nextLong() & 0xFFFF_FFFFL;
    }

    private boolean accepted(String body) {
        Map<String, Object> parsed = ChannelHttpSupport.json(body);
        if (parsed.isEmpty()) return true;
        return intValue(parsed.get("ret")) == 0 && intValue(parsed.get("errcode")) == 0;
    }

    private boolean looksLikeFileName(String text) {
        String lower = text.toLowerCase();
        return lower.matches(".*\\.(txt|doc|docx|pdf|jpg|jpeg|png|gif|mp4|avi|mov|mp3|wav|zip|rar|xlsx|xls|ppt|pptx)$");
    }

    private boolean duplicate(String agentId, Map<String, Object> payload) {
        String id = ChannelHttpSupport.first(payload, "context_token", "msg_id", "id", "message_id");
        if (id.isBlank()) return false;
        Map<String, Long> ids = processed.computeIfAbsent(key(agentId), ignored -> new ConcurrentHashMap<>());
        Long previous = ids.putIfAbsent(id, System.currentTimeMillis());
        if (ids.size() > PROCESSED_LIMIT) {
            long cutoff = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
            ids.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            if (ids.size() > PROCESSED_LIMIT) {
                ids.clear();
                ids.put(id, System.currentTimeMillis());
            }
        }
        return previous != null;
    }

    private int intValue(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return 0;
        }
    }

    private String base(Map<String, Object> config) {
        String base = stringValue(config.get("base_url"));
        return ChannelHttpSupport.trimSlash(base.isBlank() ? DEFAULT_BASE : base);
    }

    private boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    @Override
    public void close() {
        tasks.values().forEach(task -> task.cancel(true));
        executor.shutdownNow();
    }
}

class QQChannelAdapter extends BasicChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(QQChannelAdapter.class);
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final int INTENTS = (1 << 30) | (1 << 12) | (1 << 25) | (1 << 1) | (1 << 26);
    private static final int TEXT_CHUNK_SIZE = 1800;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Integer> seq = new ConcurrentHashMap<>();

    QQChannelAdapter() {
        super("qq", true, false, true, true);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        List<String> missing = ChannelHttpSupport.missing(config, "app_id", "client_secret");
        if (!Boolean.TRUE.equals(config.get("enabled")) || !missing.isEmpty()) {
            return CompletableFuture.completedFuture(health(agentId, config));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                connect(agentId, config, dispatcher);
                super.start(agentId, config);
            } catch (Exception ignored) {
            }
            return health(agentId, config);
        });
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        String key = key(agentId);
        WebSocket ws = sockets.remove(key);
        if (ws != null) ws.abort();
        ScheduledFuture<?> hb = heartbeats.remove(key);
        if (hb != null) hb.cancel(true);
        return super.stop(agentId);
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        try {
            String key = Base64.getEncoder().encodeToString(randomBytes(32));
            String host = portalHost(config);
            HttpResponse<String> response = ChannelHttpSupport.post("https://" + host + "/lite/create_bind_task",
                    Map.of(), Map.of("key", key));
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            if (!"0".equals(stringValue(body.get("retcode")))) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", response.body());
            }
            String taskId = stringValue(ChannelHttpSupport.map(body.get("data")).get("task_id"));
            String scanUrl = "https://" + host + "/qqbot/openclaw/connect.html?task_id="
                    + ChannelHttpSupport.encode(taskId) + "&_wv=2&source=melon";
            String token = Base64.getUrlEncoder().encodeToString(JsonUtils.toJson(Map.of("task_id", taskId, "key", key))
                    .getBytes(StandardCharsets.UTF_8));
            return Map.of("qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl), "poll_token", token,
                    "enabled", true, "status", "waiting", "detail", "");
        } catch (Exception e) {
            return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        try {
            Map<String, Object> decoded = ChannelHttpSupport.json(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            String taskId = stringValue(decoded.get("task_id"));
            String key = stringValue(decoded.get("key"));
            HttpResponse<String> response = ChannelHttpSupport.post("https://" + portalHost(config) + "/lite/poll_bind_result",
                    Map.of(), Map.of("task_id", taskId));
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            if (!"0".equals(stringValue(body.get("retcode")))) {
                return Map.of("status", "fail", "credentials", Map.of("fail_reason", body.getOrDefault("msg", "unknown")));
            }
            Map<?, ?> data = ChannelHttpSupport.map(body.get("data"));
            int status = intValue(data.get("status"));
            if (status == 2) {
                String secret = ChannelHttpSupport.aesGcmDecryptBase64(stringValue(data.get("bot_encrypt_secret")), key);
                return Map.of("status", "success", "credentials", Map.of(
                        "app_id", stringValue(data.get("bot_appid")),
                        "client_secret", secret,
                        "user_openid", stringValue(data.get("user_openid"))));
            }
            if (status == 3) return Map.of("status", "expired", "credentials", Map.of());
            return Map.of("status", "waiting", "credentials", Map.of());
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of("fail_reason", e.getMessage()));
        }
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        List<String> missing = ChannelHttpSupport.missing(config, "app_id", "client_secret");
        if (!missing.isEmpty()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        }
        boolean running = sockets.containsKey(key(agentId));
        return ChannelHealth.of(type(), running ? "running" : "configured", running, true, true,
                running ? "QQ gateway WebSocket is connected." : "QQ channel is configured.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        return inbound(agentId, body, headers);
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = token(config).value;
                Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
                String msgId = ChannelHttpSupport.first(meta, "message_id", "msg_id", "id");
                QQRoute route = resolveRoute(message, meta);
                boolean markdown = booleanValue(meta.get("markdown_enabled"), booleanValue(config.get("markdown_enabled"), true));
                boolean sent = false;
                String detail = "";
                for (String chunk : splitText(message.getText())) {
                    SendResult result = sendTextChunk(config, token, route, chunk, msgId, markdown);
                    if (!result.ok() && markdown && shouldPlainTextFallback(result.detail())) {
                        String fallbackText = ChannelHttpSupport.sanitizeQqText(chunk);
                        result = sendTextChunk(config, token, route, fallbackText, msgId, false);
                    } else if (!result.ok() && !markdown && isUrlContentError(result.detail())) {
                        result = sendTextChunk(config, token, route, aggressiveSanitizeQqText(chunk), msgId, false);
                    }
                    sent = result.ok();
                    detail = result.detail();
                    if (!sent) break;
                }
                ChannelHttpSupport.mark(message, sent ? "sent" : "failed", detail);
                if (sent) {
                    log.info("QQ outbound sent: agent={}, type={}, path={}, session={}, text_len={}",
                            message.getAgentId(), route.messageType(), route.path(), message.getSessionId(), message.getText().length());
                } else {
                    log.warn("QQ outbound failed: agent={}, type={}, path={}, session={}, detail={}",
                            message.getAgentId(), route.messageType(), route.path(), message.getSessionId(), detail);
                }
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
                log.warn("QQ send exception: agent={}, session={}, error={}",
                        message.getAgentId(), message.getSessionId(), e.toString());
            }
            return message;
        });
    }

    private QQRoute resolveRoute(ChannelOutboundMessage message, Map<?, ?> meta) {
        Map<?, ?> extra = message.getTo() != null ? message.getTo().getExtra() : Map.of();
        String messageType = firstNonBlank(
                ChannelHttpSupport.first(meta, "message_type"),
                ChannelHttpSupport.first(extra, "message_type"),
                message.getTo() != null ? stringValue(message.getTo().getKind()) : "");
        String toHandle = message.getTo() != null ? message.getTo().toHandle() : "";
        String toId = message.getTo() != null ? stringValue(message.getTo().getId()) : "";
        String senderId = firstNonBlank(
                ChannelHttpSupport.first(meta, "sender_id", "user_openid"),
                ChannelHttpSupport.first(extra, "sender_id", "user_openid"),
                handleId(toHandle, "c2c:"),
                plainQqTarget(toId),
                stringValue(message.getUserId()));
        String channelId = firstNonBlank(
                ChannelHttpSupport.first(meta, "channel_id"),
                ChannelHttpSupport.first(extra, "channel_id"),
                handleId(toHandle, "channel:"),
                "guild".equals(messageType) ? plainQqTarget(toId) : "");
        String groupOpenid = firstNonBlank(
                ChannelHttpSupport.first(meta, "group_openid"),
                ChannelHttpSupport.first(extra, "group_openid"),
                handleId(toHandle, "group:"),
                "group".equals(messageType) ? plainQqTarget(toId) : "");
        String guildId = firstNonBlank(
                ChannelHttpSupport.first(meta, "guild_id"),
                ChannelHttpSupport.first(extra, "guild_id"),
                handleId(toHandle, "dm:"),
                "dm".equals(messageType) ? plainQqTarget(toId) : "");
        if (messageType.isBlank()) {
            if (toHandle.startsWith("group:") || !groupOpenid.isBlank()) {
                messageType = "group";
            } else if (toHandle.startsWith("channel:") || !channelId.isBlank()) {
                messageType = "guild";
            } else if (toHandle.startsWith("dm:") || !guildId.isBlank()) {
                messageType = "dm";
            } else {
                messageType = "c2c";
            }
        }
        if ("group".equals(messageType) && !groupOpenid.isBlank()) {
            return new QQRoute(messageType, "/v2/groups/" + ChannelHttpSupport.encode(groupOpenid) + "/messages", true, "group");
        }
        if ("guild".equals(messageType) && !channelId.isBlank()) {
            return new QQRoute(messageType, "/channels/" + ChannelHttpSupport.encode(channelId) + "/messages", false, "");
        }
        if ("dm".equals(messageType) && !guildId.isBlank()) {
            return new QQRoute(messageType, "/dms/" + ChannelHttpSupport.encode(guildId) + "/messages", false, "");
        }
        if (senderId.isBlank()) {
            throw new IllegalStateException("QQ outbound target is empty: type=" + messageType + ", session=" + message.getSessionId());
        }
        return new QQRoute("c2c", "/v2/users/" + ChannelHttpSupport.encode(senderId) + "/messages", true, "c2c");
    }

    private SendResult sendTextChunk(Map<String, Object> config,
                                     String token,
                                     QQRoute route,
                                     String text,
                                     String msgId,
                                     boolean markdown) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (markdown) {
            body.put("markdown", Map.of("content", text));
            if (route.useSeq()) body.put("msg_type", 2);
        } else {
            body.put("content", text);
            if (route.useSeq()) body.put("msg_type", 0);
        }
        if (route.useSeq()) {
            body.put("msg_seq", nextMsgSeq(msgId.isBlank() ? route.seqKey() : msgId));
        }
        if (!msgId.isBlank()) body.put("msg_id", msgId);
        HttpResponse<String> response = ChannelHttpSupport.post(apiBase(config) + route.path(),
                Map.of("Authorization", "QQBot " + token), body);
        String detail = response.statusCode() + " " + response.body();
        return new SendResult(qqAccepted(response), detail);
    }

    private int nextMsgSeq(String key) {
        String seqKey = key == null || key.isBlank() ? "default" : key;
        return seq.merge(seqKey, 1, Integer::sum);
    }

    private boolean qqAccepted(HttpResponse<String> response) {
        if (!ok(response)) return false;
        Map<String, Object> body = ChannelHttpSupport.json(response.body());
        if (body.isEmpty()) return true;
        for (String key : List.of("code", "errcode", "ret", "retcode", "error_code")) {
            Object raw = body.get(key);
            if (raw == null) continue;
            String value = stringValue(raw);
            if (!value.isBlank() && !"0".equals(value)) return false;
        }
        return true;
    }

    private List<String> splitText(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < value.length(); start += TEXT_CHUNK_SIZE) {
            int end = Math.min(value.length(), start + TEXT_CHUNK_SIZE);
            chunks.add(value.substring(start, end));
        }
        return chunks;
    }

    private boolean shouldPlainTextFallback(String detail) {
        String text = detail != null ? detail.toLowerCase() : "";
        return text.contains("markdown") || text.contains("50056") || text.contains("40034012")
                || text.contains("不允许发送原生 markdown");
    }

    private boolean isUrlContentError(String detail) {
        String text = detail != null ? detail.toLowerCase() : "";
        return text.contains("url") || text.contains("链接") || text.contains("domain");
    }

    private String aggressiveSanitizeQqText(String text) {
        return ChannelHttpSupport.sanitizeQqText(text).replaceAll("\\b[\\w][\\w.-]*\\.(com|cn|org|net|edu|gov|io|co|cc|tv|me|info|biz|app|dev|top|xyz)(/\\S*)?", "[链接已省略]");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String handleId(String value, String prefix) {
        String text = stringValue(value);
        return text.startsWith(prefix) ? text.substring(prefix.length()) : "";
    }

    private String plainQqTarget(String value) {
        String text = stringValue(value);
        if (text.isBlank() || "default".equals(text)) return "";
        if (text.startsWith("qq:group:") || text.startsWith("qq:channel:")) return "";
        if (text.startsWith("qq:")) return text.substring(3);
        if (text.startsWith("group:") || text.startsWith("channel:") || text.startsWith("dm:") || text.startsWith("c2c:")) return "";
        return text;
    }

    private void connect(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) throws Exception {
        String key = key(agentId);
        if (sockets.containsKey(key)) return;
        String token = token(config).value;
        HttpResponse<String> gateway = ChannelHttpSupport.get(apiBase(config) + "/gateway",
                Map.of("Authorization", "QQBot " + token, "Content-Type", "application/json"));
        String url = stringValue(ChannelHttpSupport.json(gateway.body()).get("url"));
        if (url.isBlank()) throw new IllegalStateException("QQ gateway url is empty: " + gateway.body());
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(url), new QQListener(agentId, config, dispatcher, token))
                .join();
        sockets.put(key, ws);
    }

    private ChannelInboundMessage inbound(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> data = payload.containsKey("d") ? ChannelHttpSupport.map(payload.get("d")) : payload;
        String event = stringValue(payload.get("t"), stringValue(payload.get("event_type")));
        Map<?, ?> author = ChannelHttpSupport.map(data.get("author"));
        Map<?, ?> member = ChannelHttpSupport.map(data.get("member"));
        String messageType = switch (event) {
            case "GROUP_AT_MESSAGE_CREATE" -> "group";
            case "AT_MESSAGE_CREATE" -> "guild";
            case "DIRECT_MESSAGE_CREATE" -> "dm";
            default -> "c2c";
        };
        String sender = switch (messageType) {
            case "group" -> firstNonBlank(
                    ChannelHttpSupport.first(author, "member_openid", "id"),
                    ChannelHttpSupport.first(data, "member_openid", "user_openid", "id"),
                    ChannelHttpSupport.first(member, "member_openid", "user_id"));
            case "guild", "dm" -> firstNonBlank(
                    ChannelHttpSupport.first(author, "id", "username"),
                    ChannelHttpSupport.first(data, "user_openid", "id"));
            default -> firstNonBlank(
                    ChannelHttpSupport.first(author, "user_openid", "id"),
                    ChannelHttpSupport.first(data, "user_openid", "id"));
        };
        String group = firstNonBlank(ChannelHttpSupport.first(data, "group_openid"), ChannelHttpSupport.first(author, "group_openid"));
        String channel = firstNonBlank(ChannelHttpSupport.first(data, "channel_id"), ChannelHttpSupport.first(author, "channel_id"));
        String guild = firstNonBlank(ChannelHttpSupport.first(data, "guild_id"), ChannelHttpSupport.first(author, "guild_id"));
        String msgId = ChannelHttpSupport.first(data, "id", "message_id", "msg_id");
        String session = "group".equals(messageType) && !group.isBlank() ? "qq:group:" + group
                : "guild".equals(messageType) && !channel.isBlank() ? "qq:channel:" + channel
                : "dm".equals(messageType) && !guild.isBlank() ? "qq:dm:" + guild
                : "qq:" + sender;
        Map<String, Object> meta = ChannelHttpSupport.copy(data);
        meta.put("message_type", messageType);
        meta.put("message_id", msgId);
        meta.put("sender_id", sender);
        meta.put("user_name", ChannelHttpSupport.first(author, "username", "nick", "nickname"));
        meta.put("is_group", "group".equals(messageType) || "guild".equals(messageType));
        meta.put("incoming_raw", ChannelHttpSupport.copy(data));
        meta.put("attachments", ChannelHttpSupport.list(data.get("attachments")));
        if (!group.isBlank()) meta.put("group_openid", group);
        if (!channel.isBlank()) meta.put("channel_id", channel);
        if (!guild.isBlank()) meta.put("guild_id", guild);
        String replyId = switch (messageType) {
            case "group" -> group;
            case "guild" -> channel;
            case "dm" -> guild;
            default -> sender;
        };
        String toHandle = switch (messageType) {
            case "group" -> "group:" + group;
            case "guild" -> "channel:" + channel;
            case "dm" -> "dm:" + guild;
            default -> "c2c:" + sender;
        };
        Map<String, Object> replyExtra = new LinkedHashMap<>();
        replyExtra.put("sender_id", sender);
        replyExtra.put("group_openid", group);
        replyExtra.put("channel_id", channel);
        replyExtra.put("guild_id", guild);
        replyExtra.put("message_type", messageType);
        replyExtra.put("message_id", msgId);
        replyExtra.put("to_handle", toHandle);
        replyExtra.put("headers", headers != null ? headers : Map.of());
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(sender);
        inbound.setSessionId(session);
        inbound.setContent(ChannelHttpSupport.first(data, "content", "text", "message"));
        inbound.setChannelMeta(meta);
        inbound.setAttachments(qqAttachments(data));
        inbound.setReplyTo(new ChannelAddress(messageType, replyId, replyExtra));
        return inbound;
    }

    private List<Map<String, Object>> qqAttachments(Map<?, ?> data) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : ChannelHttpSupport.list(data.get("attachments"))) {
            Map<?, ?> raw = ChannelHttpSupport.map(item);
            if (raw.isEmpty()) continue;
            Map<String, Object> attachment = new LinkedHashMap<>();
            String url = ChannelHttpSupport.first(raw, "url", "download_url", "file_url");
            String name = ChannelHttpSupport.first(raw, "filename", "file_name", "name", "id");
            attachment.put("type", "data");
            attachment.put("name", name.isBlank() ? "qq-attachment" : name);
            attachment.put("filename", name.isBlank() ? "qq-attachment" : name);
            if (!url.isBlank()) {
                attachment.put("url", url);
                attachment.put("source", Map.of("type", "url", "url", url, "media_type", ChannelHttpSupport.first(raw, "content_type", "media_type")));
            }
            result.add(attachment);
        }
        return result;
    }

    private Token token(Map<String, Object> config) throws Exception {
        String cacheKey = stringValue(config.get("app_id"));
        Token cached = tokens.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt - 300_000L) return cached;
        HttpResponse<String> response = ChannelHttpSupport.post(TOKEN_URL, Map.of(),
                Map.of("appId", config.get("app_id"), "clientSecret", config.get("client_secret")));
        Map<String, Object> body = ChannelHttpSupport.json(response.body());
        String value = stringValue(body.get("access_token"));
        if (value.isBlank()) throw new IllegalStateException("QQ access_token is empty: " + response.body());
        long expires = longValue(body.get("expires_in"), 7200) * 1000L;
        Token token = new Token(value, System.currentTimeMillis() + expires);
        tokens.put(cacheKey, token);
        return token;
    }

    private String apiBase(Map<String, Object> config) {
        String base = stringValue(config.get("api_base"));
        return ChannelHttpSupport.trimSlash(base.isBlank() ? "https://api.sgroup.qq.com" : base);
    }

    private boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private long longValue(Object raw, long fallback) {
        if (raw instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception e) {
            return fallback;
        }
    }

    private int intValue(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return 0;
        }
    }

    private String portalHost(Map<String, Object> config) {
        String host = stringValue(config.get("portal_host"), stringValue(System.getenv("QQ_PORTAL_HOST"), "q.qq.com"));
        return host.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private byte[] randomBytes(int len) {
        byte[] bytes = new byte[len];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private record Token(String value, long expiresAt) {
    }

    private record QQRoute(String messageType, String path, boolean useSeq, String seqKey) {
    }

    private record SendResult(boolean ok, String detail) {
    }

    private class QQListener implements WebSocket.Listener {
        private final String agentId;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final String token;
        private final StringBuilder buffer = new StringBuilder();
        private volatile long lastSeq = 0;

        QQListener(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher, String token) {
            this.agentId = agentId;
            this.config = config;
            this.dispatcher = dispatcher;
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            Map<String, Object> payload = ChannelHttpSupport.json(buffer.toString());
            buffer.setLength(0);
            int op = intValue(payload.get("op"));
            if (payload.get("s") instanceof Number n) lastSeq = n.longValue();
            if (op == 10) {
                Map<?, ?> d = ChannelHttpSupport.map(payload.get("d"));
                int interval = intValue(d.get("heartbeat_interval"));
                identify(webSocket);
                scheduleHeartbeat(agentId, webSocket, interval > 0 ? interval : 30_000);
            } else if (op == 0) {
                if (isMessageEvent(stringValue(payload.get("t")))) {
                    ChannelInboundMessage inbound = inbound(agentId, payload, Map.of());
                    if (!inbound.getUserId().isBlank()
                            && (!inbound.getContent().isBlank()
                            || (inbound.getAttachments() != null && !inbound.getAttachments().isEmpty()))) {
                        dispatcher.dispatch(inbound, 20);
                    }
                }
            } else if (op == 7 || op == 9) {
                webSocket.abort();
                sockets.remove(key(agentId));
                executor.schedule(() -> {
                    try {
                        connect(agentId, config, dispatcher);
                    } catch (Exception ignored) {
                    }
                }, 2, TimeUnit.SECONDS);
            }
            webSocket.request(1);
            return null;
        }

        private boolean isMessageEvent(String event) {
            return "C2C_MESSAGE_CREATE".equals(event)
                    || "GROUP_AT_MESSAGE_CREATE".equals(event)
                    || "AT_MESSAGE_CREATE".equals(event)
                    || "DIRECT_MESSAGE_CREATE".equals(event);
        }

        private void identify(WebSocket webSocket) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", 2);
            body.put("d", Map.of(
                    "token", "QQBot " + token,
                    "intents", INTENTS,
                    "shard", List.of(0, 1),
                    "properties", Map.of()));
            webSocket.sendText(JsonUtils.toJson(body), true);
        }

        private void scheduleHeartbeat(String agentId, WebSocket webSocket, int intervalMs) {
            ScheduledFuture<?> old = heartbeats.remove(key(agentId));
            if (old != null) old.cancel(true);
            heartbeats.put(key(agentId), executor.scheduleAtFixedRate(() -> {
                Map<String, Object> body = Map.of("op", 1, "d", lastSeq);
                webSocket.sendText(JsonUtils.toJson(body), true);
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS));
        }

        private int intValue(Object raw) {
            if (raw instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(raw));
            } catch (Exception e) {
                return 0;
            }
        }
    }

    @Override
    public void close() {
        sockets.values().forEach(WebSocket::abort);
        heartbeats.values().forEach(task -> task.cancel(true));
        executor.shutdownNow();
    }
}

class WeComChannelAdapter extends BasicChannelAdapter {
    private static final String WECOM_AUTH_ORIGIN = "https://work.weixin.qq.com";

    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    WeComChannelAdapter() {
        super("wecom", true, false, true, true);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        if (!Boolean.TRUE.equals(config.get("enabled")) || !ChannelHttpSupport.missing(config, "bot_id", "secret").isEmpty()) {
            return CompletableFuture.completedFuture(health(agentId, config));
        }
        String wsUrl = stringValue(config.get("ws_url"));
        if (!wsUrl.isBlank() && !sockets.containsKey(agentId)) {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .header("x-bot-id", stringValue(config.get("bot_id")))
                    .header("x-secret", stringValue(config.get("secret")))
                    .buildAsync(URI.create(wsUrl), new JsonChannelListener(agentId, type(), dispatcher, this::parseWebhook))
                    .thenAccept(ws -> sockets.put(agentId, ws));
        }
        return super.start(agentId, config);
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        WebSocket ws = sockets.remove(agentId);
        if (ws != null) ws.abort();
        return super.stop(agentId);
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        try {
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(ChannelHttpSupport.randomHex(12).getBytes(StandardCharsets.UTF_8));
            String url = WECOM_AUTH_ORIGIN + "/ai/qc/gen?source=melon&state=" + ChannelHttpSupport.encode(state)
                    + "&timestamp=" + System.currentTimeMillis();
            HttpResponse<String> response = ChannelHttpSupport.get(url, Map.of());
            java.util.regex.Matcher matcher = Pattern.compile("window\\.settings\\s*=\\s*(\\{[^<]+\\})").matcher(response.body());
            if (!matcher.find()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", "Failed to parse WeCom auth page settings");
            }
            Map<String, Object> settings = ChannelHttpSupport.json(matcher.group(1));
            String scode = stringValue(settings.get("scode"));
            String authUrl = stringValue(settings.get("auth_url"));
            if (scode.isBlank() || authUrl.isBlank()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", "WeCom returned empty scode or auth_url");
            }
            return Map.of("qrcode_img", ChannelHttpSupport.qrPngBase64(authUrl), "poll_token", scode,
                    "enabled", true, "status", "waiting", "detail", "");
        } catch (Exception e) {
            return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", stringValue(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        try {
            String url = WECOM_AUTH_ORIGIN + "/ai/qc/query_result?scode=" + ChannelHttpSupport.encode(token);
            HttpResponse<String> response = ChannelHttpSupport.get(url, Map.of());
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            Map<?, ?> data = ChannelHttpSupport.map(body.get("data"));
            Map<?, ?> botInfo = ChannelHttpSupport.map(data.get("bot_info"));
            return Map.of("status", stringValue(data.get("status"), "waiting"), "credentials", Map.of(
                    "bot_id", stringValue(botInfo.get("botid")),
                    "secret", stringValue(botInfo.get("secret"))));
        } catch (Exception e) {
            return Map.of("status", "failed", "credentials", Map.of(), "detail", stringValue(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        List<String> missing = ChannelHttpSupport.missing(config, "bot_id", "secret");
        if (!missing.isEmpty()) return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        boolean running = sockets.containsKey(agentId) || stringValue(config.get("ws_url")).isBlank();
        return ChannelHealth.of(type(), running ? "running" : "configured", running, true, true, "WeCom adapter is configured.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        String sender = ChannelHttpSupport.first(body, "sender_id", "userid", "user_id", "from_user_id");
        String chatid = ChannelHttpSupport.first(body, "chatid", "chat_id", "conversation_id");
        String chatType = ChannelHttpSupport.first(body, "chat_type");
        String text = ChannelHttpSupport.first(body, "content", "text", "message");
        Map<?, ?> textMap = ChannelHttpSupport.map(body.get("text"));
        if (text.isBlank()) text = stringValue(textMap.get("content"));
        String session = "group".equals(chatType) && !chatid.isBlank() ? "wecom:group:" + chatid : "wecom:" + sender;
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(sender);
        inbound.setSessionId(session);
        inbound.setContent(text);
        inbound.setChannelMeta(ChannelHttpSupport.copy(body));
        inbound.setReplyTo(new ChannelAddress("wecom", session, Map.of(
                "wecom_sender_id", sender,
                "wecom_chatid", chatid,
                "wecom_chat_type", chatType,
                "reply_url", ChannelHttpSupport.first(body, "reply_url", "webhook_url"),
                "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = replyUrl(message, config);
                Map<String, Object> body = Map.of(
                        "chatid", ChannelHttpSupport.target(message, "wecom_chatid", "chatid"),
                        "msgtype", "markdown",
                        "markdown", Map.of("content", message.getText()));
                HttpResponse<String> response = ChannelHttpSupport.post(url, auth(config), body);
                ChannelHttpSupport.mark(message, response.statusCode() < 300 ? "sent" : "failed", response.statusCode() + " " + response.body());
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
            }
            return message;
        });
    }

    private String replyUrl(ChannelOutboundMessage message, Map<String, Object> config) {
        Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
        String url = ChannelHttpSupport.first(meta, "reply_url", "webhook_url");
        if (url.isBlank()) url = ChannelHttpSupport.first(config, "reply_url", "webhook_url", "api_base");
        if (url.isBlank()) throw new IllegalArgumentException("Missing WeCom reply_url/webhook_url/api_base");
        return url;
    }

    private Map<String, String> auth(Map<String, Object> config) {
        return Map.of("x-bot-id", stringValue(config.get("bot_id")), "x-secret", stringValue(config.get("secret")));
    }
}

class XiaoYiChannelAdapter extends BasicChannelAdapter {
    private static final String PRIMARY = "wss://hag.cloud.huawei.com/openclaw/v1/ws/link";
    private static final String BACKUP = "wss://116.63.174.231/openclaw/v1/ws/link";
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, String> sessionServers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

    XiaoYiChannelAdapter() {
        super("xiaoyi", true, false, true, true);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        if (!Boolean.TRUE.equals(config.get("enabled")) || !ChannelHttpSupport.missing(config, "ak", "sk", "agent_id").isEmpty()) {
            return CompletableFuture.completedFuture(health(agentId, config));
        }
        connect(agentId, "primary", stringValue(config.getOrDefault("ws_url", PRIMARY), PRIMARY), config, dispatcher);
        connect(agentId, "backup", stringValue(config.getOrDefault("backup_ws_url", BACKUP), BACKUP), config, dispatcher);
        return super.start(agentId, config);
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        sockets.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(agentId + ":")) {
                entry.getValue().abort();
                return true;
            }
            return false;
        });
        return super.stop(agentId);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        List<String> missing = ChannelHttpSupport.missing(config, "ak", "sk", "agent_id");
        if (!missing.isEmpty()) return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        boolean running = sockets.keySet().stream().anyMatch(key -> key.startsWith(agentId + ":"));
        return ChannelHealth.of(type(), running ? "running" : "configured", running, true, true, "XiaoYi adapter is configured.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        Map<?, ?> params = ChannelHttpSupport.map(body.get("params"));
        Map<?, ?> msg = ChannelHttpSupport.map(params.get("message"));
        String session = stringValue(params.get("sessionId"), stringValue(body.get("sessionId")));
        String taskId = stringValue(params.get("id"), stringValue(body.get("id")));
        String text = extractA2AText(msg);
        Map<String, Object> meta = ChannelHttpSupport.copy(body);
        meta.put("task_id", taskId);
        meta.put("message_id", body.getOrDefault("id", UUID.randomUUID().toString()));
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(session);
        inbound.setSessionId("xiaoyi:" + session);
        inbound.setContent(text);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("a2a", session, Map.of("task_id", taskId, "message_id", meta.get("message_id"), "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
        String session = message.getSessionId().replaceFirst("^xiaoyi:", "");
        String taskId = ChannelHttpSupport.first(meta, "task_id");
        String messageId = ChannelHttpSupport.first(meta, "message_id");
        if (messageId.isBlank()) messageId = UUID.randomUUID().toString();
        Map<String, Object> jsonRpc = Map.of(
                "jsonrpc", "2.0",
                "id", messageId,
                "result", Map.of("taskId", taskId, "artifact", Map.of(
                        "parts", List.of(Map.of("kind", "text", "text", message.getText())),
                        "append", true,
                        "lastChunk", true)));
        Map<String, Object> frame = Map.of(
                "msgType", "agent_response",
                "agentId", stringValue(config.get("agent_id")),
                "sessionId", session,
                "taskId", taskId,
                "msgDetail", JsonUtils.toJson(jsonRpc));
        String server = sessionServers.getOrDefault(session, "primary");
        WebSocket ws = sockets.get(message.getAgentId() + ":" + server);
        if (ws != null) {
            ws.sendText(JsonUtils.toJson(frame), true);
            ChannelHttpSupport.mark(message, "sent", "websocket");
        } else {
            ChannelHttpSupport.mark(message, "failed", "No XiaoYi WebSocket for session " + session);
        }
        return CompletableFuture.completedFuture(message);
    }

    private void connect(String agentId, String server, String url, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        String key = agentId + ":" + server;
        if (url.isBlank() || sockets.containsKey(key)) return;
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpClient.newHttpClient().newWebSocketBuilder()
                .header("x-access-key", stringValue(config.get("ak")))
                .header("x-sign", ChannelHttpSupport.hmacSha256Base64(stringValue(config.get("sk")), timestamp))
                .header("x-ts", timestamp)
                .header("x-agent-id", stringValue(config.get("agent_id")))
                .buildAsync(URI.create(url), new JsonChannelListener(agentId, type(), dispatcher, (a, body, headers) -> {
                    ChannelInboundMessage inbound = parseWebhook(a, body, headers);
                    sessionServers.put(inbound.getSessionId().replaceFirst("^xiaoyi:", ""), server);
                    return inbound;
                }))
                .thenAccept(ws -> {
                    sockets.put(key, ws);
                    ws.sendText(JsonUtils.toJson(Map.of(
                            "msgType", "clawd_bot_init",
                            "agentId", stringValue(config.get("agent_id")),
                            "msgDetail", JsonUtils.toJson(Map.of("agentId", config.get("agent_id"))))), true);
                    executor.scheduleAtFixedRate(() -> ws.sendText(JsonUtils.toJson(Map.of(
                            "msgType", "heartbeat",
                            "agentId", stringValue(config.get("agent_id")),
                            "msgDetail", JsonUtils.toJson(Map.of("timestamp", System.currentTimeMillis())))), true), 30, 30, TimeUnit.SECONDS);
                });
    }

    private String extractA2AText(Map<?, ?> msg) {
        String direct = ChannelHttpSupport.first(msg, "text", "content");
        if (!direct.isBlank()) return direct;
        StringBuilder text = new StringBuilder();
        for (Object part : ChannelHttpSupport.list(msg.get("parts"))) {
            Map<?, ?> p = ChannelHttpSupport.map(part);
            String value = ChannelHttpSupport.first(p, "text", "content");
            if (!value.isBlank()) text.append(value);
        }
        return text.toString();
    }

    @Override
    public void close() {
        sockets.values().forEach(WebSocket::abort);
        executor.shutdownNow();
    }
}

class YuanbaoChannelAdapter extends BasicChannelAdapter {
    private static final String DEFAULT_WS = "wss://bot-wss.yuanbao.tencent.com/wss/connection";

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, Boolean> authed = new ConcurrentHashMap<>();
    private final Map<String, String> botIds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    YuanbaoChannelAdapter() {
        super("yuanbao", true, false, true, true);
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        if (Boolean.TRUE.equals(config.get("enabled")) && ChannelHttpSupport.missing(config, "app_id", "app_secret").isEmpty()) {
            try {
                connect(agentId, config, dispatcher);
                super.start(agentId, config);
            } catch (Exception ignored) {
            }
        }
        return CompletableFuture.completedFuture(health(agentId, config));
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        WebSocket ws = sockets.remove(key(agentId));
        if (ws != null) ws.abort();
        authed.remove(key(agentId));
        return super.stop(agentId);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        List<String> missing = ChannelHttpSupport.missing(config, "app_id", "app_secret");
        if (!missing.isEmpty()) return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        boolean running = Boolean.TRUE.equals(authed.get(key(agentId)));
        return ChannelHealth.of(type(), running ? "running" : "configured", running, true, true,
                running ? "Yuanbao WebSocket is authenticated." : "Yuanbao adapter is configured.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        String user = ChannelHttpSupport.first(body, "user_id", "openid", "from_openid", "sender_id", "from_account");
        String group = ChannelHttpSupport.first(body, "group_id", "group_openid", "group_code");
        String session = !group.isBlank() ? "yuanbao:group:" + group : "yuanbao:" + user;
        String text = ChannelHttpSupport.first(body, "content", "text", "message");
        if (text.isBlank()) text = textFromMsgBody(body.get("msg_body"));
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(user);
        inbound.setSessionId(session);
        inbound.setContent(text);
        inbound.setChannelMeta(ChannelHttpSupport.copy(body));
        inbound.setReplyTo(new ChannelAddress(group.isBlank() ? "c2c" : "group", session,
                Map.of("user_id", user, "group_id", group, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WebSocket ws = sockets.get(key(message.getAgentId()));
                if (ws != null && Boolean.TRUE.equals(authed.get(key(message.getAgentId())))) {
                    Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
                    String group = ChannelHttpSupport.first(meta, "group_code", "group_id", "group_openid");
                    String target = !group.isBlank() ? group : ChannelHttpSupport.target(message, "user_id", "from_account", "openid");
                    String botId = botIds.getOrDefault(key(message.getAgentId()), "");
                    byte[] payload = !group.isBlank()
                            ? YuanbaoProto.sendGroup(target, botId, message.getText())
                            : YuanbaoProto.sendC2C(target, botId, message.getText());
                    ws.sendBinary(ByteBuffer.wrap(payload), true).join();
                    ChannelHttpSupport.mark(message, "sent", "websocket");
                } else {
                    sendHttpFallback(message, config);
                }
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
            }
            return message;
        });
    }

    private void connect(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) throws Exception {
        String cacheKey = key(agentId);
        if (sockets.containsKey(cacheKey)) return;
        Token token = token(config);
        botIds.put(cacheKey, token.botId);
        String wsUrl = stringValue(config.get("websocket_url"), DEFAULT_WS);
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new YuanbaoListener(agentId, config, dispatcher, token))
                .join();
        sockets.put(cacheKey, ws);
        ws.sendBinary(ByteBuffer.wrap(YuanbaoProto.authBind(token.botId, token.source, token.value)), true);
    }

    private void sendHttpFallback(ChannelOutboundMessage message, Map<String, Object> config) throws Exception {
        Token token = token(config);
        String url = ChannelHttpSupport.first(config, "send_url", "webhook_url", "api_base");
        if (url.isBlank()) throw new IllegalArgumentException("Yuanbao WebSocket is not connected and send_url/webhook_url/api_base is missing");
        Map<String, Object> body = Map.of(
                "to", ChannelHttpSupport.target(message, "user_id", "group_id"),
                "text", message.getText(),
                "session_id", message.getSessionId());
        HttpResponse<String> response = ChannelHttpSupport.post(url, Map.of(
                "X-ID", token.botId,
                "X-Token", token.value,
                "X-Source", token.source), body);
        ChannelHttpSupport.mark(message, response.statusCode() < 300 ? "sent" : "failed", response.statusCode() + " " + response.body());
    }

    private Token token(Map<String, Object> config) throws Exception {
        String appId = stringValue(config.get("app_id"));
        Token cached = tokens.get(appId);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt - 300_000L) return cached;
        String nonce = ChannelHttpSupport.randomHex(16);
        String timestamp = ChannelHttpSupport.beijingTimestamp();
        String secret = stringValue(config.get("app_secret"));
        String signature = ChannelHttpSupport.hmacSha256Hex(secret, nonce + timestamp + appId + secret);
        String domain = stringValue(config.get("api_domain"), "bot.yuanbao.tencent.com")
                .replaceFirst("^https?://", "");
        HttpResponse<String> response = ChannelHttpSupport.post("https://" + ChannelHttpSupport.trimSlash(domain) + "/api/v5/robotLogic/sign-token",
                Map.of(), Map.of("app_key", appId, "nonce", nonce, "signature", signature, "timestamp", timestamp));
        Map<String, Object> body = ChannelHttpSupport.json(response.body());
        Map<?, ?> data = ChannelHttpSupport.map(body.get("data"));
        String value = stringValue(data.get("token"));
        if (value.isBlank()) throw new IllegalStateException("Yuanbao token is empty: " + response.body());
        long duration = data.get("duration") instanceof Number n ? n.longValue() : 3600L;
        Token token = new Token(stringValue(data.get("bot_id")), value, stringValue(data.get("source"), "bot"),
                System.currentTimeMillis() + duration * 1000L);
        tokens.put(appId, token);
        return token;
    }

    private ChannelInboundMessage inboundFromPush(String agentId, byte[] data) {
        Map<String, Object> payload = ChannelHttpSupport.json(new String(data, StandardCharsets.UTF_8));
        if (payload.containsKey("msg_body")) {
            Object raw = payload.get("msg_body");
            List<Object> normalized = new ArrayList<>();
            for (Object item : ChannelHttpSupport.list(raw)) {
                Map<String, Object> part = ChannelHttpSupport.copy(ChannelHttpSupport.map(item));
                Object content = part.get("msg_content");
                if (content instanceof String text) {
                    Map<String, Object> parsed = ChannelHttpSupport.json(text);
                    part.put("msg_content", parsed.isEmpty() ? Map.of("text", text) : parsed);
                }
                normalized.add(part);
            }
            payload.put("msg_body", normalized);
        }
        return parseWebhook(agentId, payload, Map.of("websocket_channel", "yuanbao"));
    }

    private String textFromMsgBody(Object raw) {
        StringBuilder text = new StringBuilder();
        for (Object item : ChannelHttpSupport.list(raw)) {
            Map<?, ?> part = ChannelHttpSupport.map(item);
            Map<?, ?> content = ChannelHttpSupport.map(part.get("msg_content"));
            if (content.isEmpty() && part.get("msg_content") instanceof String rawText) {
                content = ChannelHttpSupport.json(rawText);
            }
            String value = ChannelHttpSupport.first(content, "text", "desc", "fileName", "url");
            if (!value.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private class YuanbaoListener implements WebSocket.Listener {
        private final String agentId;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final Token token;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        YuanbaoListener(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher, Token token) {
            this.agentId = agentId;
            this.config = config;
            this.dispatcher = dispatcher;
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buffer.writeBytes(chunk);
            if (last) {
                byte[] raw = buffer.toByteArray();
                buffer.reset();
                handleFrame(webSocket, raw);
            }
            webSocket.request(1);
            return null;
        }

        private void handleFrame(WebSocket webSocket, byte[] raw) {
            YuanbaoProto.ConnMsg msg = YuanbaoProto.decodeConn(raw);
            if ("auth-bind".equals(msg.cmd())) {
                if (msg.status() == 0 || msg.status() == 41101) {
                    authed.put(key(agentId), true);
                    executor.scheduleAtFixedRate(() -> webSocket.sendBinary(ByteBuffer.wrap(YuanbaoProto.ping()), true),
                            5, 5, TimeUnit.SECONDS);
                } else {
                    authed.put(key(agentId), false);
                }
                return;
            }
            if ("ping".equals(msg.cmd())) return;
            if (msg.needAck()) {
                webSocket.sendBinary(ByteBuffer.wrap(YuanbaoProto.pushAck(msg)), true);
            }
            if (msg.cmdType() == 2 && msg.data() != null && msg.data().length > 0) {
                ChannelInboundMessage inbound = inboundFromPush(agentId, msg.data());
                if (!inbound.getContent().isBlank()) dispatcher.dispatch(inbound, 20);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sockets.remove(key(agentId));
            authed.remove(key(agentId));
            if (Boolean.TRUE.equals(config.get("enabled"))) {
                executor.schedule(() -> {
                    try {
                        connect(agentId, config, dispatcher);
                    } catch (Exception ignored) {
                    }
                }, 5, TimeUnit.SECONDS);
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }

    private record Token(String botId, String value, String source, long expiresAt) {
    }

    @Override
    public void close() {
        sockets.values().forEach(WebSocket::abort);
        executor.shutdownNow();
    }
}

class VoiceChannelAdapter extends BasicChannelAdapter {
    VoiceChannelAdapter() {
        super("voice", true, false, false, true);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        List<String> missing = ChannelHttpSupport.missing(config, "twilio_account_sid", "twilio_auth_token", "phone_number_sid");
        if (!missing.isEmpty()) return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        return super.health(agentId, config);
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        String callSid = ChannelHttpSupport.first(body, "CallSid", "call_sid", "session_id");
        String from = ChannelHttpSupport.first(body, "From", "from_number", "user_id");
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(from);
        inbound.setSessionId("voice:" + callSid);
        inbound.setContent(ChannelHttpSupport.first(body, "SpeechResult", "transcript", "text", "message"));
        inbound.setChannelMeta(ChannelHttpSupport.copy(body));
        inbound.setReplyTo(new ChannelAddress("call", callSid, Map.of("call_sid", callSid, "from_number", from, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        ChannelHttpSupport.mark(message, "sent", "voice replies are returned as TwiML by webhook");
        return CompletableFuture.completedFuture(message);
    }

    String twiml(String text, Map<String, Object> config) {
        String language = stringValue(config.get("language"), "zh-CN");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say language=\""
                + escape(language) + "\">" + escape(text) + "</Say><Gather input=\"speech\" method=\"POST\" speechTimeout=\"auto\"/></Response>";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}

class SipChannelAdapter extends BasicChannelAdapter {
    SipChannelAdapter() {
        super("sip", true, false, false, true);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        if ("livekit".equals(stringValue(config.get("sip_mode"))) || "production".equals(stringValue(config.get("sip_mode")))) {
            List<String> missing = ChannelHttpSupport.missing(config, "livekit_url", "livekit_api_key", "livekit_api_secret");
            if (!missing.isEmpty()) return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        }
        return super.health(agentId, config);
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        String call = ChannelHttpSupport.first(body, "call_id", "participant_identity", "sip_call_id", "session_id");
        String from = ChannelHttpSupport.first(body, "from", "phone_number", "caller", "user_id");
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(from);
        inbound.setSessionId("sip:" + call);
        inbound.setContent(ChannelHttpSupport.first(body, "transcript", "text", "message"));
        inbound.setChannelMeta(ChannelHttpSupport.copy(body));
        inbound.setReplyTo(new ChannelAddress("sip", call, Map.of("call_id", call, "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        String url = ChannelHttpSupport.first(config, "reply_url", "webhook_url");
        if (url.isBlank()) {
            ChannelHttpSupport.mark(message, "sent", "SIP/LiveKit response stored in channel output; no reply_url configured");
            return CompletableFuture.completedFuture(message);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> response = ChannelHttpSupport.post(url, Map.of(), Map.of(
                        "call_id", ChannelHttpSupport.target(message, "call_id"),
                        "text", message.getText()));
                ChannelHttpSupport.mark(message, response.statusCode() < 300 ? "sent" : "failed", response.statusCode() + " " + response.body());
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
            }
            return message;
        });
    }
}

interface WebhookParser {
    ChannelInboundMessage parse(String agentId, Map<String, Object> body, Map<String, String> headers);
}

class JsonChannelListener implements WebSocket.Listener {
    private final String agentId;
    private final String channel;
    private final ChannelInboundDispatcher dispatcher;
    private final WebhookParser parser;
    private final StringBuilder buffer = new StringBuilder();

    JsonChannelListener(String agentId, String channel, ChannelInboundDispatcher dispatcher, WebhookParser parser) {
        this.agentId = agentId;
        this.channel = channel;
        this.dispatcher = dispatcher;
        this.parser = parser;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            Map<String, Object> body = ChannelHttpSupport.json(buffer.toString());
            buffer.setLength(0);
            ChannelInboundMessage inbound = parser.parse(agentId, body, Map.of("websocket_channel", channel));
            dispatcher.dispatch(inbound, 20);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        webSocket.request(1);
        return null;
    }
}
