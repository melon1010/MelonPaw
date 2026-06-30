/**
 * @author melon
 */
package com.melon.coding.lsp;

import com.melon.core.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Detects available LSP servers on the system.
 * Corresponds to Python LSP server detection logic.
 */
public class LspServerDetector {

    private static final Logger log = LoggerFactory.getLogger(LspServerDetector.class);

    /**
     * Default LSP server commands by language.
     */
    private static final Map<String, String[]> DEFAULT_COMMANDS = Map.of(
        "java", new String[]{"jdtls"},
        "python", new String[]{"pylsp"},
        "typescript", new String[]{"typescript-language-server", "--stdio"},
        "javascript", new String[]{"typescript-language-server", "--stdio"},
        "go", new String[]{"gopls"},
        "rust", new String[]{"rust-analyzer"},
        "c", new String[]{"clangd"},
        "cpp", new String[]{"clangd"},
        "csharp", new String[]{"OmniSharp", "-lsp"}
    );

    /**
     * Detects all available LSP servers on the system.
     *
     * @return Map of language -> command array
     */
    public Map<String, String[]> detectAvailableServers() {
        Map<String, String[]> available = new LinkedHashMap<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            pathEnv = "";
        }

        String[] pathDirs = pathEnv.split(File.pathSeparator);
        for (Map.Entry<String, String[]> entry : DEFAULT_COMMANDS.entrySet()) {
            String language = entry.getKey();
            String[] command = entry.getValue();
            String executable = command[0];

            // On Windows, check for .exe, .bat, .cmd extensions
            if (PlatformUtil.isWindows()) {
                executable = findExecutableOnWindows(executable, pathDirs);
            } else {
                executable = findOnPath(executable, pathDirs);
            }

            if (executable != null) {
                String[] fullCommand = new String[command.length];
                fullCommand[0] = executable;
                System.arraycopy(command, 1, fullCommand, 1, command.length - 1);
                available.put(language, fullCommand);
                log.debug("Found LSP server for {}: {}", language, executable);
            }
        }

        log.info("Detected {} LSP servers: {}", available.size(), available.keySet());
        return available;
    }

    /**
     * Detects LSP servers for a specific language.
     */
    public String[] detectServerForLanguage(String language) {
        String[] command = DEFAULT_COMMANDS.get(language);
        if (command == null) {
            log.warn("No default LSP server configured for language: {}", language);
            return null;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] pathDirs = pathEnv.split(File.pathSeparator);
        String executable = command[0];

        if (PlatformUtil.isWindows()) {
            executable = findExecutableOnWindows(executable, pathDirs);
        } else {
            executable = findOnPath(executable, pathDirs);
        }

        if (executable == null) {
            log.debug("LSP server '{}' not found on PATH for language {}", command[0], language);
            return null;
        }

        String[] fullCommand = new String[command.length];
        fullCommand[0] = executable;
        System.arraycopy(command, 1, fullCommand, 1, command.length - 1);
        return fullCommand;
    }

    private String findOnPath(String executable, String[] pathDirs) {
        for (String dir : pathDirs) {
            Path file = Path.of(dir, executable);
            if (Files.isExecutable(file)) {
                return file.toString();
            }
        }
        return null;
    }

    private String findExecutableOnWindows(String executable, String[] pathDirs) {
        String[] extensions = {".exe", ".bat", ".cmd", ""};
        for (String dir : pathDirs) {
            for (String ext : extensions) {
                Path file = Path.of(dir, executable + ext);
                if (Files.exists(file)) {
                    return file.toString();
                }
            }
        }
        return null;
    }

    /**
     * Returns the list of supported languages.
     */
    public Set<String> getSupportedLanguages() {
        return DEFAULT_COMMANDS.keySet();
    }
}
