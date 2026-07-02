package com.melon.app.controller;

import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Frontend compatibility for provider OAuth endpoints.
 *
 * <p>Provider/model CRUD lives under /api/models. The copied frontend still
 * calls these /api/providers OAuth URLs, so keep stable disabled responses.</p>
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    @PostMapping("/{providerId}/oauth/start")
    public Mono<Map<String, Object>> startOAuth(@PathVariable String providerId) {
        return Mono.just(Map.of(
                "authorize_url", "",
                "state", UUID.randomUUID().toString(),
                "flow_type", "disabled",
                "provider_id", providerId
        ));
    }

    @GetMapping("/{providerId}/oauth/status")
    public Mono<Map<String, Object>> oauthStatus(@PathVariable String providerId,
                                                 @RequestParam(required = false) String state) {
        return Mono.just(Map.of("status", "disabled", "provider_id", providerId));
    }
}
