/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.config.MelonConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 配置管理. 对应 Python /api/config.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Autowired
    private ConfigManager configManager;

    @GetMapping
    public Mono<MelonConfig> get() {
        return Mono.just(configManager.getConfig());
    }

    @PutMapping
    public Mono<String> save() {
        configManager.save();
        return Mono.just("saved");
    }
}
