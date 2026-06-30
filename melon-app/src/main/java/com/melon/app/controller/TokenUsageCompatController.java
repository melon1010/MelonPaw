/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.TokenUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * QwenPaw frontend-compatible token usage API aliases.
 */
@RestController
@RequestMapping("/api/token-usage")
public class TokenUsageCompatController {

    private final TokenUsageService tokenUsageService;

    public TokenUsageCompatController(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> summary() {
        return Mono.fromCallable(() -> {
            Map<String, Object> total = tokenUsageService.getTotalUsage();
            long input = numberValue(total.get("input_tokens"));
            long output = numberValue(total.get("output_tokens"));
            return ResponseEntity.ok(Map.of(
                    "total_prompt_tokens", input,
                    "total_completion_tokens", output,
                    "total_calls", numberValue(total.get("call_count")),
                    "by_model", Map.of(),
                    "by_date", Map.of()
            ));
        });
    }

    @GetMapping("/details")
    public Mono<ResponseEntity<?>> details() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    private long numberValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
