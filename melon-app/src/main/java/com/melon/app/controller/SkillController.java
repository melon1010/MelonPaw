/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.SkillService;
import com.melon.core.security.ScanResult;
import com.melon.core.security.SkillScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 技能管理 API. 对应 Python /api/skills.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillService skillService;
    private final SkillScanner skillScanner;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
        this.skillScanner = new SkillScanner();
    }

    /**
     * 列出所有技能.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listSkills() {
        return Mono.fromCallable(() -> {
            try {
                var skills = skillService.listSkills();
                return ResponseEntity.ok(skills);
            } catch (Exception e) {
                log.error("Failed to list skills", e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 获取单个技能内容.
     */
    @GetMapping("/{name}")
    public Mono<ResponseEntity<?>> getSkill(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            try {
                String content = skillService.getSkillContent(name);
                return ResponseEntity.ok(Map.of("name", name, "content", content));
            } catch (java.nio.file.NoSuchFileException e) {
                return ResponseEntity.notFound().build();
            } catch (Exception e) {
                log.error("Failed to get skill: {}", name, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 创建新技能. 先进行安全扫描.
     */
    @PostMapping
    public Mono<ResponseEntity<?>> createSkill(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                String name = body.get("name");
                String content = body.getOrDefault("content", "");

                if (name == null || name.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Skill name is required"));
                }

                // Security scan before creating
                ScanResult scanResult = skillScanner.scan(name, content);
                if (!scanResult.isSafe()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Skill failed security scan",
                            "issues", scanResult.getIssues()
                    ));
                }

                skillService.createSkill(name, content);
                return ResponseEntity.ok(Map.of("status", "created", "name", name));
            } catch (Exception e) {
                log.error("Failed to create skill", e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 删除技能.
     */
    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<?>> deleteSkill(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            try {
                skillService.deleteSkill(name);
                return ResponseEntity.ok(Map.of("status", "deleted", "name", name));
            } catch (Exception e) {
                log.error("Failed to delete skill: {}", name, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }
}
