package com.melon.app.controller;

import com.melon.channels.ChannelAccessControlStore;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelHealth;
import com.melon.channels.ChannelManager;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

@RestController
@RequestMapping("/api")
public class ChannelCompatController {

    private final ChannelConfigService configService;
    private final ChannelManager channelManager;
    private final ChannelAccessControlStore accessControlStore;
    private final ChannelAdapterRegistry registry;

    public ChannelCompatController(ChannelConfigService configService,
                                   ChannelManager channelManager,
                                   ChannelAccessControlStore accessControlStore,
                                   ChannelAdapterRegistry registry) {
        this.configService = configService;
        this.channelManager = channelManager;
        this.accessControlStore = accessControlStore;
        this.registry = registry;
    }

    @GetMapping("/config/channels/types")
    public Mono<ResponseEntity<?>> channelTypes() {
        return Mono.just(ResponseEntity.ok(configService.types()));
    }

    @GetMapping("/config/channels/types/meta")
    public Mono<ResponseEntity<?>> channelTypesMeta() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String type : configService.types()) {
            result.put(type, configService.typeMeta(type));
        }
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/config/channels")
    public Mono<ResponseEntity<?>> channels(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(ResponseEntity.ok(configService.list(agent(agentId))));
    }

    @PutMapping("/config/channels")
    public Mono<ResponseEntity<?>> updateChannels(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        String currentAgent = agent(agentId);
        Map<String, Map<String, Object>> updated = configService.updateAll(currentAgent, body);
        var futures = updated.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> Boolean.TRUE.equals(entry.getValue().get("enabled"))
                        ? channelManager.start(currentAgent, entry.getKey())
                        : channelManager.stop(currentAgent, entry.getKey()))
                .toArray(java.util.concurrent.CompletableFuture[]::new);
        return Mono.fromFuture(java.util.concurrent.CompletableFuture.allOf(futures))
                .thenReturn(ResponseEntity.ok(updated));
    }

    @GetMapping("/config/channels/{channel}")
    public Mono<ResponseEntity<?>> channel(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(configService.get(agent(agentId), channel)));
    }

    @PutMapping("/config/channels/{channel}")
    public Mono<ResponseEntity<?>> updateChannel(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String channel,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String currentAgent = agent(agentId);
        Map<String, Object> updated = configService.update(currentAgent, channel, body != null ? body : Map.of());
        if (Boolean.TRUE.equals(updated.get("enabled"))) {
            return Mono.fromFuture(channelManager.start(currentAgent, channel))
                    .thenReturn(ResponseEntity.ok(updated));
        }
        return Mono.fromFuture(channelManager.stop(currentAgent, channel))
                .thenReturn(ResponseEntity.ok(updated));
    }

    @PostMapping("/config/channels/{channel}/start")
    public Mono<ResponseEntity<?>> start(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                         @PathVariable String channel) {
        return Mono.fromFuture(channelManager.start(agent(agentId), channel).thenApply(ChannelHealth::toMap))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/config/channels/{channel}/stop")
    public Mono<ResponseEntity<?>> stop(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                        @PathVariable String channel) {
        return Mono.fromFuture(channelManager.stop(agent(agentId), channel).thenApply(ChannelHealth::toMap))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/config/channels/{channel}/restart")
    public Mono<ResponseEntity<?>> restart(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String channel) {
        return Mono.fromFuture(channelManager.restart(agent(agentId), channel).thenApply(ChannelHealth::toMap))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/config/channels/{channel}/health")
    public Mono<ResponseEntity<?>> health(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                          @PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(channelManager.health(agent(agentId), channel).toMap()));
    }

    @GetMapping("/config/channels/health")
    public Mono<ResponseEntity<?>> healthAll(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(ResponseEntity.ok(channelManager.healthAll(agent(agentId))));
    }

    @GetMapping("/config/channels/{channel}/qrcode")
    public Mono<ResponseEntity<?>> channelQrcode(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String channel,
                                                 @RequestParam(required = false) Map<String, String> params) {
        String currentAgent = agent(agentId);
        return Mono.fromCallable(() -> registry.get(channel).qrcode(currentAgent, runtimeConfig(currentAgent, channel, params)))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/config/channels/{channel}/qrcode/status")
    public Mono<ResponseEntity<?>> channelQrcodeStatus(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String channel,
                                                       @RequestParam(required = false) String token,
                                                       @RequestParam(required = false) Map<String, String> params) {
        String currentAgent = agent(agentId);
        return Mono.fromCallable(() -> {
                    Map<String, Object> result = registry.get(channel).qrcodeStatus(currentAgent, runtimeConfig(currentAgent, channel, params), token);
                    activateChannelAfterQrSuccess(currentAgent, channel, result);
                    return result;
                })
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void activateChannelAfterQrSuccess(String agentId, String channel, Map<String, Object> result) {
        if (!"success".equals(String.valueOf(result.get("status")))) return;
        Object rawCredentials = result.get("credentials");
        if (!(rawCredentials instanceof Map<?, ?> credentials) || credentials.isEmpty()) return;
        Map<String, Object> update = new LinkedHashMap<>();
        credentials.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (value != null && !String.valueOf(value).isBlank() && !"fail_reason".equals(name)) {
                update.put(name, value);
            }
        });
        if (update.isEmpty()) return;
        update.put("enabled", true);
        configService.update(agentId, channel, update);
        channelManager.restart(agentId, channel).exceptionally(error -> null);
    }

    @PostMapping(value = "/channels/voice/webhook",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<ResponseEntity<String>> voiceWebhookForm(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                         @RequestHeader Map<String, String> headers,
                                                         @RequestParam Map<String, String> form) {
        Map<String, Object> payload = new LinkedHashMap<>(form);
        String currentAgent = agent(agentId);
        return Mono.fromFuture(channelManager.handleWebhook(currentAgent, "voice", payload, headers))
                .map(out -> ResponseEntity.ok(twiml(out.getText(), configService.runtimeConfig(currentAgent, "voice"))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/channels/{channel}/webhook")
    public Mono<ResponseEntity<?>> webhook(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String channel,
                                           @RequestHeader Map<String, String> headers,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        ResponseEntity<?> challenge = challengeResponse(channel, payload);
        if (challenge != null) return Mono.just(challenge);
        return Mono.fromFuture(channelManager.handleWebhook(agent(agentId), channel,
                        payload, headers))
                .<ResponseEntity<?>>map(out -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    Map<String, Object> meta = out.getMeta() != null ? out.getMeta() : Map.of();
                    result.put("sent", !Boolean.TRUE.equals(meta.get("ignored")));
                    result.put("channel", out.getChannel());
                    result.put("session_id", out.getSessionId());
                    result.put("user_id", out.getUserId());
                    result.put("text", out.getText());
                    result.put("meta", meta);
                    return ResponseEntity.ok(result);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<?> challengeResponse(String channel, Map<String, Object> body) {
        Object challenge = body.get("challenge");
        if (challenge == null) return null;
        if ("slack".equals(channel)) {
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }
        if ("feishu".equals(channel)) {
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }
        return ResponseEntity.ok(Map.of("challenge", challenge));
    }

    @GetMapping("/channels/{channel}/websocket")
    public Mono<ResponseEntity<?>> websocket(@PathVariable String channel) {
        boolean available = "onebot".equals(channel);
        return Mono.just(ResponseEntity.ok(Map.of(
                "channel", channel,
                "available", available,
                "url", available ? "/api/channels/onebot/websocket" : "",
                "detail", available ? "Use WebSocket upgrade on this URL." : "This channel does not expose a Spring WebSocket endpoint."
        )));
    }

    @GetMapping("/access-control")
    public Mono<ResponseEntity<?>> accessControlAll(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(ResponseEntity.ok(accessControlStore.all(agent(agentId))));
    }

    @GetMapping("/access-control/pending/all")
    public Mono<ResponseEntity<?>> accessControlPendingAll(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(ResponseEntity.ok(accessControlStore.pendingAll(agent(agentId))));
    }

    @GetMapping("/access-control/{channel}")
    public Mono<ResponseEntity<?>> accessControl(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(accessControlStore.channel(agent(agentId), channel)));
    }

    @PostMapping("/access-control/whitelist/add")
    public Mono<ResponseEntity<?>> addWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "whitelist", entries(body))));
    }

    @PostMapping("/access-control/{channel}/whitelist")
    public Mono<ResponseEntity<?>> addChannelWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String channel,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "whitelist", List.of(entry(channel, body)))));
    }

    @PostMapping("/access-control/whitelist/remove")
    public Mono<ResponseEntity<?>> removeWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "whitelist", entries(body))));
    }

    @DeleteMapping("/access-control/{channel}/whitelist/{userId}")
    public Mono<ResponseEntity<?>> removeChannelWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                          @PathVariable String channel,
                                                          @PathVariable String userId) {
        return Mono.just(ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "whitelist", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/blacklist/add")
    public Mono<ResponseEntity<?>> addBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "blacklist", entries(body))));
    }

    @PostMapping("/access-control/{channel}/blacklist")
    public Mono<ResponseEntity<?>> addChannelBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String channel,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "blacklist", List.of(entry(channel, body)))));
    }

    @PostMapping("/access-control/blacklist/remove")
    public Mono<ResponseEntity<?>> removeBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "blacklist", entries(body))));
    }

    @DeleteMapping("/access-control/{channel}/blacklist/{userId}")
    public Mono<ResponseEntity<?>> removeChannelBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                          @PathVariable String channel,
                                                          @PathVariable String userId) {
        return Mono.just(ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "blacklist", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/remark")
    public Mono<ResponseEntity<?>> updateRemark(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.updateRemark(agent(agentId),
                stringValue(body != null ? body.get("channel") : null, "console"),
                stringValue(body != null ? body.get("user_id") : null),
                stringValue(body != null ? body.get("remark") : null))));
    }

    @PostMapping("/access-control/username")
    public Mono<ResponseEntity<?>> updateUsername(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.updateUsername(agent(agentId),
                stringValue(body != null ? body.get("channel") : null, "console"),
                stringValue(body != null ? body.get("user_id") : null),
                stringValue(body != null ? body.get("username") : null))));
    }

    @PostMapping("/access-control/pending/{action}")
    public Mono<ResponseEntity<?>> pendingAction(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String action,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(accessControlStore.pendingAction(agent(agentId), action, entries(body))));
    }

    @PostMapping("/access-control/{channel}/pending/{userId}/approve")
    public Mono<ResponseEntity<?>> approveChannelPending(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                         @PathVariable String channel,
                                                         @PathVariable String userId) {
        return Mono.just(ResponseEntity.ok(accessControlStore.pendingAction(agent(agentId), "approve", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/{channel}/pending/{userId}/deny")
    public Mono<ResponseEntity<?>> denyChannelPending(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                      @PathVariable String channel,
                                                      @PathVariable String userId) {
        return Mono.just(ResponseEntity.ok(accessControlStore.pendingAction(agent(agentId), "deny", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/pending/remark")
    public Mono<ResponseEntity<?>> pendingRemark(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return updateRemark(agentId, body);
    }

    private String agent(String agentId) {
        return AgentRequestSupport.agentId(agentId);
    }

    private Map<String, Object> runtimeConfig(String agentId, String channel, Map<String, String> params) {
        Map<String, Object> config = new LinkedHashMap<>(configService.runtimeConfig(agentId, channel));
        if (params != null) {
            params.forEach((key, value) -> {
                if (!"token".equals(key)) config.put(key, value);
            });
        }
        return config;
    }

    private String twiml(String text, Map<String, Object> config) {
        String language = stringValue(config.getOrDefault("language", "zh-CN"), "zh-CN");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say language=\""
                + xml(language) + "\">" + xml(text) + "</Say><Gather input=\"speech\" method=\"POST\" speechTimeout=\"auto\"/></Response>";
    }

    private String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private Map<String, Object> entry(String channel, Map<String, Object> body) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (body != null) entry.putAll(body);
        entry.put("channel", channel);
        Object userId = entry.get("user_id");
        if (userId == null) userId = entry.get("userId");
        if (userId != null) entry.put("user_id", userId);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(Map<String, Object> body) {
        Object raw = body != null ? body.get("entries") : null;
        if (!(raw instanceof List<?> items)) return List.of();
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    ((Map<?, ?>) item).forEach((key, value) -> copy.put(String.valueOf(key), value));
                    return copy;
                })
                .toList();
    }
}
