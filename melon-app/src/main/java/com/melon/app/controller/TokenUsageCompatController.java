package com.melon.app.controller;

import com.melon.app.service.TokenUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

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
    public Mono<ResponseEntity<?>> summary(@RequestParam(value = "start_date", required = false) String startDate,
                                           @RequestParam(value = "end_date", required = false) String endDate,
                                           @RequestParam(value = "provider", required = false) String provider,
                                           @RequestParam(value = "model", required = false) String model) {
        return Mono.fromCallable(() -> ResponseEntity.ok(tokenUsageService.getSummary(startDate, endDate, provider, model)));
    }

    @GetMapping("/details")
    public Mono<ResponseEntity<?>> details(@RequestParam(value = "start_date", required = false) String startDate,
                                           @RequestParam(value = "end_date", required = false) String endDate,
                                           @RequestParam(value = "provider", required = false) String provider,
                                           @RequestParam(value = "model", required = false) String model) {
        return Mono.fromCallable(() -> ResponseEntity.ok(tokenUsageService.getDetails(startDate, endDate, provider, model)));
    }
}
