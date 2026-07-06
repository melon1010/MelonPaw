package com.melon.app.channel;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.app.runner.QwenPawEnvelopeMapper;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelInboundMessage;
import com.melon.channels.ChannelMessageRenderer;
import com.melon.channels.ChannelOutboundMessage;
import com.melon.core.config.ConfigManager;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class ChannelRuntimeProcessor {

    private final AgentRunner agentRunner;
    private final ChatManager chatManager;
    private final ConfigManager configManager;
    private final ChannelConfigService channelConfigService;
    private final ChannelMessageRenderer messageRenderer = new ChannelMessageRenderer();

    public ChannelRuntimeProcessor(AgentRunner agentRunner,
                                   ChatManager chatManager,
                                   ConfigManager configManager,
                                   ChannelConfigService channelConfigService) {
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
        this.configManager = configManager;
        this.channelConfigService = channelConfigService;
    }

    public CompletableFuture<ChannelOutboundMessage> process(ChannelInboundMessage inbound) {
        return CompletableFuture.supplyAsync(() -> run(inbound));
    }

    private ChannelOutboundMessage run(ChannelInboundMessage inbound) {
        String agentId = value(inbound.getAgentId(), "default");
        String channel = value(inbound.getChannel(), "console");
        String userId = value(inbound.getUserId(), "default");
        String sessionId = value(inbound.getSessionId(), channel + "-" + userId);
        Map<String, Object> channelConfig = channelConfigService.runtimeConfig(agentId, channel);
        inbound.setAttachments(normalizeAttachments(agentId, inbound, channelConfig));
        String text = agentText(inbound);
        List<Map<String, Object>> frontendContext = new ArrayList<>();
        frontendContext.add(userMessage(inbound.getContent(), inbound.getAttachments()));
        ChatSpec chat = chatManager.getOrCreateForSession(agentId, sessionId, userId, channel, title(text));
        chatManager.setStatus(agentId, chat.getId(), "running");
        QwenPawEnvelopeMapper envelope = new QwenPawEnvelopeMapper(sessionId);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("agent_id", agentId);
        env.put("channel", channel);
        env.put("source", channel);
        env.put("session_id", sessionId);
        env.put("working_dir", configManager.resolveWorkspaceDir(agentId).toString());
        env.put("channel_meta", inbound.getChannelMeta());
        try {
            agentRunner.stream(agentId, List.of(new UserMessage(text)), userId, sessionId, env)
                    .doOnNext(envelope::translate)
                    .blockLast();
            envelope.finish();
            String reply = messageRenderer.render(envelope.outputMessagesSnapshot(), channelConfig);
            return outbound(inbound, reply.isBlank() ? envelope.visibleAssistantText() : reply, envelope.visibleAssistantText());
        } catch (Exception e) {
            envelope.error(e);
            return outbound(inbound, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), "");
        } finally {
            chatManager.setStatus(agentId, chat.getId(), "idle");
            chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId,
                    sessionContext(frontendContext, envelope.outputStateMessagesSnapshot()), envelope.turnUsageSnapshot());
        }
    }

    private ChannelOutboundMessage outbound(ChannelInboundMessage inbound, String text, String visibleText) {
        ChannelOutboundMessage out = new ChannelOutboundMessage();
        out.setAgentId(inbound.getAgentId());
        out.setChannel(inbound.getChannel());
        out.setUserId(inbound.getUserId());
        out.setSessionId(inbound.getSessionId());
        out.setTo(inbound.getReplyTo());
        out.setText(text != null ? text : "");
        out.setMeta(Map.of("channel_meta", inbound.getChannelMeta(), "visible_text", visibleText != null ? visibleText : ""));
        return out;
    }

    private Map<String, Object> userMessage(String text, List<Map<String, Object>> attachments) {
        java.util.ArrayList<Map<String, Object>> content = new java.util.ArrayList<>();
        content.add(Map.of("type", "text", "text", text != null ? text : ""));
        for (Map<String, Object> attachment : attachments != null ? attachments : List.<Map<String, Object>>of()) {
            Map<String, Object> block = new LinkedHashMap<>(attachment);
            block.putIfAbsent("type", "data");
            content.add(block);
        }
        return new LinkedHashMap<>(Map.of(
                "id", "channel_user_" + UUID.randomUUID().toString().replace("-", ""),
                "role", "user",
                "name", "user",
                "content", content
        ));
    }

    private List<Map<String, Object>> sessionContext(List<Map<String, Object>> userMessages,
                                                     List<Map<String, Object>> outputMessages) {
        List<Map<String, Object>> context = new ArrayList<>(userMessages != null ? userMessages : List.of());
        if (outputMessages != null) context.addAll(outputMessages);
        return context;
    }

    private String agentText(ChannelInboundMessage inbound) {
        StringBuilder text = new StringBuilder(inbound.getContent() != null ? inbound.getContent() : "");
        for (Map<String, Object> attachment : inbound.getAttachments() != null ? inbound.getAttachments() : List.<Map<String, Object>>of()) {
            Object path = firstPresent(attachment, "path", "url", "file_url", "image_url", "name", "filename");
            if ((path == null || String.valueOf(path).isBlank()) && attachment.get("source") instanceof Map<?, ?> source) {
                path = firstPresent(toStringMap(source), "url", "data");
            }
            if (path != null && !String.valueOf(path).isBlank()) {
                text.append("\n用户上传文件，已经下载到 ").append(filePathText(String.valueOf(path)));
            }
        }
        return text.toString();
    }

    private List<Map<String, Object>> normalizeAttachments(String agentId, ChannelInboundMessage inbound, Map<String, Object> config) {
        List<Map<String, Object>> raw = new ArrayList<>(inbound.getAttachments() != null ? inbound.getAttachments() : List.of());
        raw.addAll(extractAttachments(inbound.getChannelMeta()));
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> attachment : raw) {
            Map<String, Object> block = normalizeAttachment(agentId, inbound.getChannel(), attachment, config);
            if (!block.isEmpty()) normalized.add(block);
        }
        return normalized;
    }

    private List<Map<String, Object>> extractAttachments(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : List.of("attachments", "files", "images", "videos", "audios")) {
            Object value = meta.get(key);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) result.add(toStringMap(map));
                }
            }
        }
        if (firstPresent(meta, "file_url", "image_url", "video_url", "audio_url", "download_url", "media_url",
                "url_private_download", "url_private", "url_download") != null) {
            result.add(new LinkedHashMap<>(meta));
        }
        return result;
    }

    private Map<String, Object> normalizeAttachment(String agentId, String channel, Map<String, Object> attachment, Map<String, Object> config) {
        if (attachment == null || attachment.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>(attachment);
        String name = fileName(copy);
        String mediaType = mediaType(copy, name);
        String url = sourceUrl(copy);
        if ("matrix".equals(channel) && url.startsWith("mxc://")) {
            url = matrixMediaUrl(url, config);
        }
        String path = "";
        if ("telegram".equals(channel) && firstPresent(copy, "file_id", "fileId") != null) {
            path = downloadTelegramFile(agentId, stringValue(firstPresent(copy, "file_id", "fileId")), name, config);
        } else if ("mattermost".equals(channel) && firstPresent(copy, "file_id", "id") != null && url.isBlank()) {
            path = downloadMattermostFile(agentId, stringValue(firstPresent(copy, "file_id", "id")), name, config);
        } else if (isRemote(url)) {
            path = downloadRemote(agentId, url, name, config, downloadHeaders(channel, config));
            copy.put("original_url", url);
        } else if (!url.isBlank()) {
            path = filePathText(url);
        }
        if (path.isBlank()) path = stringValue(firstPresent(copy, "path", "local_path"));
        if (path.isBlank() && url.isBlank()) return copy;
        String fileUrl = toFileUrl(path.isBlank() ? url : path);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "data");
        block.put("name", name);
        block.put("filename", name);
        if (!path.isBlank()) block.put("path", path);
        block.put("source", Map.of("type", "url", "url", fileUrl, "media_type", mediaType));
        if (copy.get("original_url") != null) block.put("original_url", copy.get("original_url"));
        return block;
    }

    private String downloadTelegramFile(String agentId, String fileId, String name, Map<String, Object> config) {
        try {
            String token = stringValue(config.get("bot_token"));
            if (token.isBlank() || fileId.isBlank()) return "";
            String base = trimSlash(stringValue(config.get("base_url")));
            if (base.isBlank()) base = "https://api.telegram.org";
            HttpResponse<String> fileInfo = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build()
                    .send(HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/getFile?file_id=" + java.net.URLEncoder.encode(fileId, StandardCharsets.UTF_8)))
                            .timeout(java.time.Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = com.melon.core.util.JsonUtils.fromJson(fileInfo.body(), Map.class);
            Object result = body != null ? body.get("result") : null;
            if (!(result instanceof Map<?, ?> resultMap)) return "";
            String filePath = stringValue(resultMap.get("file_path"));
            if (filePath.isBlank()) return "";
            String fileUrl = base + "/file/bot" + token + "/" + filePath;
            String resolvedName = name;
            if ("channel-file".equals(name)) {
                String tail = filePath.substring(filePath.lastIndexOf('/') + 1);
                if (!tail.isBlank()) resolvedName = tail;
            }
            return downloadRemote(agentId, fileUrl, resolvedName, config, Map.of());
        } catch (Exception ignored) {
            return "";
        }
    }

    private String downloadMattermostFile(String agentId, String fileId, String name, Map<String, Object> config) {
        String base = trimSlash(stringValue(config.get("url")));
        if (base.isBlank() || fileId.isBlank()) return "";
        return downloadRemote(agentId, base + "/api/v4/files/" + fileId,
                name, config, Map.of("Authorization", "Bearer " + stringValue(config.get("bot_token"))));
    }

    private String downloadRemote(String agentId, String url, String name, Map<String, Object> config, Map<String, String> headers) {
        try {
            Path mediaDir = mediaDir(agentId, config);
            Files.createDirectories(mediaDir);
            Path target = mediaDir.resolve(UUID.randomUUID().toString().replace("-", "") + "_" + safeFileName(name)).normalize();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(45))
                    .GET();
            if (headers != null) headers.forEach(requestBuilder::header);
            HttpRequest request = requestBuilder.build();
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return url;
            try (InputStream in = response.body()) {
                Files.copy(in, target);
            }
            return target.toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return url;
        }
    }

    private Path mediaDir(String agentId, Map<String, Object> config) {
        String configured = stringValue(config != null ? config.get("media_dir") : null);
        return configured.isBlank()
                ? configManager.resolveWorkspaceDir(agentId).resolve("media")
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private String sourceUrl(Map<String, Object> attachment) {
        if (attachment.get("source") instanceof Map<?, ?> source) {
            String value = stringValue(firstPresent(toStringMap(source), "url", "data"));
            if (!value.isBlank()) return value;
        }
        return stringValue(firstPresent(attachment,
                "path", "local_path", "url", "file_url", "image_url", "video_url", "audio_url",
                "download_url", "media_url", "url_private_download", "url_private", "url_download"));
    }

    private String fileName(Map<String, Object> attachment) {
        String name = stringValue(firstPresent(attachment, "file_name", "filename", "name", "title"));
        if (!name.isBlank()) return safeFileName(name);
        String url = sourceUrl(attachment);
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isBlank()) {
                String tail = path.substring(path.lastIndexOf('/') + 1);
                if (!tail.isBlank()) return safeFileName(URLDecoder.decode(tail, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "channel-file";
    }

    private String mediaType(Map<String, Object> attachment, String name) {
        String explicit = stringValue(firstPresent(attachment, "media_type", "mime_type", "content_type", "type"));
        if (explicit.contains("/")) return explicit;
        String lower = name.toLowerCase();
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) return "image/*";
        if (lower.matches(".*\\.(mp4|mov|avi|mkv|webm)$")) return "video/*";
        if (lower.matches(".*\\.(mp3|wav|m4a|ogg|flac)$")) return "audio/*";
        return "application/octet-stream";
    }

    private String filePathText(String value) {
        if (value == null) return "";
        if (value.startsWith("file://")) {
            try {
                return Path.of(URI.create(value)).toString();
            } catch (Exception ignored) {
                return value.substring("file://".length());
            }
        }
        return value;
    }

    private String toFileUrl(String value) {
        if (value == null || value.isBlank() || value.startsWith("file://") || value.startsWith("http://")
                || value.startsWith("https://") || value.startsWith("data:")) {
            return value;
        }
        return Path.of(value).toAbsolutePath().normalize().toUri().toString();
    }

    private boolean isRemote(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private Map<String, String> downloadHeaders(String channel, Map<String, Object> config) {
        if ("slack".equals(channel)) {
            String token = stringValue(config.get("bot_token"));
            return token.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + token);
        }
        if ("matrix".equals(channel)) {
            String token = stringValue(config.get("access_token"));
            return token.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + token);
        }
        return Map.of();
    }

    private String matrixMediaUrl(String mxcUrl, Map<String, Object> config) {
        String homeserver = trimSlash(stringValue(config.get("homeserver")));
        if (homeserver.isBlank() || !mxcUrl.startsWith("mxc://")) return mxcUrl;
        String rest = mxcUrl.substring("mxc://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return mxcUrl;
        String server = rest.substring(0, slash);
        String mediaId = rest.substring(slash + 1);
        return homeserver + "/_matrix/media/v3/download/" + server + "/" + mediaId;
    }

    private String trimSlash(String value) {
        while (value != null && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value == null ? "" : value;
    }

    private Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isBlank() ? "channel-file" : cleaned;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String title(String text) {
        if (text == null || text.isBlank()) return "Channel message";
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
