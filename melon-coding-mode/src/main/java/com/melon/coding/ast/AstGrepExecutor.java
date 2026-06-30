/**
 * @author melon
 */
package com.melon.coding.ast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes ast-grep commands for structural code search.
 * Corresponds to Python ast-grep integration.
 */
public class AstGrepExecutor {

    private static final Logger log = LoggerFactory.getLogger(AstGrepExecutor.class);

    private final String astGrepPath;
    private final int timeoutSeconds;

    public AstGrepExecutor(String astGrepPath, int timeoutSeconds) {
        this.astGrepPath = astGrepPath != null && !astGrepPath.isBlank() ? astGrepPath : "ast-grep";
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    /**
     * Runs an ast-grep search with the given pattern and language.
     *
     * @param pattern   AST pattern to search for
     * @param language  Language identifier (e.g., "java", "python")
     * @param searchDir Directory to search in
     * @return JSON results from ast-grep
     */
    public String search(String pattern, String language, String searchDir) {
        List<String> command = new ArrayList<>();
        command.add(astGrepPath);
        command.add("run");
        command.add("--pattern");
        command.add(pattern);
        if (language != null && !language.isBlank()) {
            command.add("--lang");
            command.add(language);
        }
        command.add("--json");
        if (searchDir != null && !searchDir.isBlank()) {
            command.add(searchDir);
        }

        return execute(command, new File(searchDir != null ? searchDir : "."));
    }

    /**
     * Runs an ast-grep search with a custom rule file.
     *
     * @param ruleFile  Path to the YAML rule file
     * @param searchDir Directory to search in
     * @return JSON results from ast-grep
     */
    public String searchWithRule(String ruleFile, String searchDir) {
        List<String> command = new ArrayList<>();
        command.add(astGrepPath);
        command.add("run");
        command.add(ruleFile);
        command.add("--json");
        if (searchDir != null && !searchDir.isBlank()) {
            command.add(searchDir);
        }

        return execute(command, new File(searchDir != null ? searchDir : "."));
    }

    /**
     * Rewrites code using an ast-grep pattern and replacement.
     *
     * @param pattern    AST pattern to match
     * @param replacement Replacement string
     * @param language   Language identifier
     * @param targetDir  Directory to rewrite in
     * @return Output from ast-grep
     */
    public String rewrite(String pattern, String replacement, String language, String targetDir) {
        List<String> command = new ArrayList<>();
        command.add(astGrepPath);
        command.add("run");
        command.add("--pattern");
        command.add(pattern);
        command.add("--rewrite");
        command.add(replacement);
        if (language != null && !language.isBlank()) {
            command.add("--lang");
            command.add(language);
        }
        command.add("--update");
        if (targetDir != null && !targetDir.isBlank()) {
            command.add(targetDir);
        }

        return execute(command, new File(targetDir != null ? targetDir : "."));
    }

    /**
     * Checks if ast-grep is available on the system.
     */
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(astGrepPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("ast-grep not available: {}", e.getMessage());
            return false;
        }
    }

    private String execute(List<String> command, File workingDir) {
        log.debug("Executing ast-grep: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: ast-grep timed out after " + timeoutSeconds + " seconds";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && !stdout.isEmpty()) {
                log.warn("ast-grep exited with code {}: {}", exitCode, stderr);
            }

            return stdout.isEmpty() ? stderr : stdout;
        } catch (IOException e) {
            log.error("Failed to execute ast-grep", e);
            return "Error: Failed to execute ast-grep: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: ast-grep execution interrupted";
        }
    }
}
