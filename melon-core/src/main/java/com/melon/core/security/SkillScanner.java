package com.melon.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 技能安全扫描器. 扫描技能文件中的危险模式, 防止恶意技能执行.
 * Corresponds to Python skill_scanner.py.
 */
public class SkillScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillScanner.class);

    /**
     * 危险命令模式 - 可能执行任意代码或删除数据.
     */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            // rm -rf variants
            Pattern.compile("(?i)rm\\s+-rf?\\s+[\"'~/]"),
            // curl/wget piped to shell
            Pattern.compile("(?i)(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh)"),
            // Download and execute
            Pattern.compile("(?i)(curl|wget)\\s+.*&&\\s*(sh|bash)"),
            // eval of remote content
            Pattern.compile("(?i)eval\\s*\\(.*http"),
            // Python exec of remote content
            Pattern.compile("(?i)exec\\s*\\(.*http"),
            // chmod 777
            Pattern.compile("(?i)chmod\\s+777"),
            // dd to disk devices
            Pattern.compile("(?i)dd\\s+.*of=/dev/"),
            // mkfs
            Pattern.compile("(?i)mkfs\\."),
            // Kill all processes
            Pattern.compile("(?i)kill\\s+-9\\s+-1"),
            Pattern.compile("(?i)killall\\s+-9"),
            // Fork bomb
            Pattern.compile(":\\(\\)\\s*\\{.*\\}"),
            // Netcat reverse shell
            Pattern.compile("(?i)nc\\s+.*-e\\s+/bin"),
            Pattern.compile("(?i)ncat\\s+.*-e\\s+/bin"),
            // Base64 decode and execute
            Pattern.compile("(?i)echo\\s+.*\\|\\s*base64.*\\|\\s*(sh|bash)"),
            // Python os.system with suspicious args
            Pattern.compile("(?i)os\\.system\\s*\\(.*rm\\s"),
    };

    /**
     * 警告模式 - 不阻止加载但提示用户注意.
     */
    private static final Pattern[] WARNING_PATTERNS = {
            // Network operations
            Pattern.compile("(?i)(curl|wget|http\\.get|requests\\.get)"),
            // Environment variable access
            Pattern.compile("(?i)os\\.environ|getenv"),
            // Subprocess calls
            Pattern.compile("(?i)subprocess\\.|os\\.system|Popen"),
            // File system writes outside workspace
            Pattern.compile("(?i)open\\s*\\(.*[\"']/(etc|var|usr|tmp)"),
            // Sudo commands
            Pattern.compile("(?i)sudo\\s"),
            // Docker commands
            Pattern.compile("(?i)docker\\s+(run|exec|rm)"),
            // Kubernetes commands
            Pattern.compile("(?i)kubectl\\s+"),
            // Cloud CLI
            Pattern.compile("(?i)(aws|gcloud|az)\\s+"),
            // SSH operations
            Pattern.compile("(?i)ssh\\s+"),
            // SCP/rsync to remote
            Pattern.compile("(?i)(scp|rsync)\\s+.*@"),
    };

    /**
     * 扫描技能文件内容, 返回扫描结果.
     * @param skillName 技能名称
     * @param content 技能文件内容
     * @return 扫描结果
     */
    public ScanResult scan(String skillName, String content) {
        ScanResult result = new ScanResult(skillName);

        for (Pattern pattern : DANGEROUS_PATTERNS) {
            var matcher = pattern.matcher(content);
            if (matcher.find()) {
                String matched = matcher.group();
                // Truncate long matches for readability
                if (matched.length() > 80) {
                    matched = matched.substring(0, 80) + "...";
                }
                result.addIssue("Dangerous pattern detected: " + pattern.pattern().substring(0, 30)
                        + "... matched: " + matched);
            }
        }

        for (Pattern pattern : WARNING_PATTERNS) {
            var matcher = pattern.matcher(content);
            if (matcher.find()) {
                String matched = matcher.group();
                if (matched.length() > 80) {
                    matched = matched.substring(0, 80) + "...";
                }
                result.addWarning("Potentially sensitive operation: " + matched);
            }
        }

        if (result.isSafe()) {
            log.debug("Skill '{}' passed security scan", skillName);
        } else {
            log.warn("Skill '{}' failed security scan: {} issues", skillName, result.getIssues().size());
        }

        return result;
    }

    /**
     * 扫描技能目录下的所有文件.
     * @param skillDir 技能目录
     * @return 扫描结果列表 (每个文件一个)
     */
    public List<ScanResult> scanDirectory(Path skillDir) {
        List<ScanResult> results = new ArrayList<>();
        String skillName = skillDir.getFileName().toString();

        if (!Files.exists(skillDir)) {
            return results;
        }

        try (var stream = Files.walk(skillDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> isScannableFile(p))
                  .forEach(file -> {
                      try {
                          String content = Files.readString(file);
                          ScanResult result = scan(skillName, content);
                          results.add(result);
                      } catch (IOException e) {
                          log.warn("Failed to read skill file: {}", file, e);
                      }
                  });
        } catch (IOException e) {
            log.error("Failed to scan skill directory: {}", skillDir, e);
        }

        return results;
    }

    /**
     * 检查文件是否应被扫描 (文本文件类型).
     */
    private boolean isScannableFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt")
                || name.endsWith(".sh") || name.endsWith(".bash")
                || name.endsWith(".py") || name.endsWith(".js")
                || name.endsWith(".ts") || name.endsWith(".java")
                || name.endsWith(".yaml") || name.endsWith(".json")
                || name.endsWith(".xml");
    }

    /**
     * 合并多个扫描结果为一个汇总结果.
     */
    public static ScanResult mergeResults(String skillName, List<ScanResult> results) {
        ScanResult merged = new ScanResult(skillName);
        for (ScanResult r : results) {
            for (String issue : r.getIssues()) {
                merged.addIssue(issue);
            }
            for (String warning : r.getWarnings()) {
                merged.addWarning(warning);
            }
        }
        return merged;
    }
}
