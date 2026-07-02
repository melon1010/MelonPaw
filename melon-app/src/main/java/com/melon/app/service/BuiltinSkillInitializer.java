package com.melon.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Seeds bundled QwenPaw skills into the shared skill pool.
 */
@Service
public class BuiltinSkillInitializer {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillInitializer.class);

    private final BuiltinSkillService builtinSkillService;

    public BuiltinSkillInitializer(BuiltinSkillService builtinSkillService) {
        this.builtinSkillService = builtinSkillService;
    }

    public void seedAllAgents() {
        seedPool();
    }

    public void seedPool() {
        try {
            builtinSkillService.seedPool();
        } catch (Exception e) {
            log.warn("Failed to seed builtin skills into pool: {}", e.getMessage());
        }
    }
}
