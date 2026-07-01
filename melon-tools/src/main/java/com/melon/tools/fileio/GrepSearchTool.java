package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 内容搜索工具. 对应 Python file_search.py:grep_search.
 * 纯 Java 实现, 不依赖外部 grep/rg 二进制.
 */
public class GrepSearchTool {

    private static final int MAX_MATCHES = 200;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "__pycache__", ".venv", "target", "build",
        ".idea", ".vscode", ".gradle", "dist", "out", ".next", ".nuxt", "coverage"
    );
    private static final Set<String> BINARY_EXTS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".pdf", ".zip",
        ".jar", ".class", ".exe", ".dll", ".so", ".bin", ".war", ".tar",
        ".gz", ".7z", ".rar", ".mp3", ".mp4", ".avi", ".mov", ".woff",
        ".woff2", ".ttf", ".eot", ".dat", ".db", ".sqlite", ".pyc"
    );

    @Tool(name = "grep_search", description = "Search file contents by regex pattern. Returns matching lines with file paths and line numbers.", readOnly = true, concurrencySafe = true)
    public String grepSearch(
            @ToolParam(name = "pattern", description = "Regex pattern to search for") String pattern,
            @ToolParam(name = "path", description = "Directory to search in (optional, defaults to workspace)") String path,
            @ToolParam(name = "is_regex", description = "Whether pattern is a regex (default true)") Boolean isRegex,
            @ToolParam(name = "case_sensitive", description = "Whether search is case sensitive (default false)") Boolean caseSensitive,
            @ToolParam(name = "context_lines", description = "Number of context lines before and after match (default 0)") Integer contextLines,
            @ToolParam(name = "include_pattern", description = "Glob pattern to filter files (optional)") String includePattern
    ) {
        if (pattern == null || pattern.isBlank()) {
            return "Error: pattern is required";
        }

        boolean regex = isRegex != null ? isRegex : true;
        boolean caseSen = caseSensitive != null ? caseSensitive : false;
        int ctxLines = contextLines != null ? contextLines : 0;

        // Compile the search pattern
        int flags = caseSen ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern searchPattern;
        try {
            if (regex) {
                searchPattern = Pattern.compile(pattern, flags);
            } else {
                // Literal search: escape regex special characters
                searchPattern = Pattern.compile(Pattern.quote(pattern), flags);
            }
        } catch (Exception e) {
            return "Error: invalid pattern: " + e.getMessage();
        }

        // Determine search path
        Path searchPath;
        if (path != null && !path.isBlank()) {
            searchPath = Path.of(path);
        } else {
            searchPath = Path.of(".");
        }

        if (!Files.exists(searchPath)) {
            return "Error: path does not exist: " + searchPath;
        }

        // Prepare include pattern matcher
        PathMatcher includeMatcher = null;
        if (includePattern != null && !includePattern.isBlank()) {
            try {
                includeMatcher = searchPath.getFileSystem().getPathMatcher("glob:" + includePattern);
            } catch (Exception e) {
                return "Error: invalid include_pattern: " + e.getMessage();
            }
        }

        final PathMatcher finalIncludeMatcher = includeMatcher;

        List<String> results = new ArrayList<>();
        AtomicInteger matchCount = new AtomicInteger(0);
        AtomicInteger outputChars = new AtomicInteger(0);

        try {
            if (Files.isRegularFile(searchPath)) {
                // Search a single file
                searchInFile(searchPath, searchPattern, ctxLines, results, matchCount, outputChars, finalIncludeMatcher);
            } else {
                // Walk the directory tree
                Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (matchCount.get() >= MAX_MATCHES || outputChars.get() >= MAX_OUTPUT_CHARS) {
                            return FileVisitResult.TERMINATE;
                        }

                        // Skip binary files by extension
                        String fileName = file.getFileName().toString();
                        int dotIdx = fileName.lastIndexOf('.');
                        if (dotIdx >= 0) {
                            String ext = fileName.substring(dotIdx).toLowerCase();
                            if (BINARY_EXTS.contains(ext)) {
                                return FileVisitResult.CONTINUE;
                            }
                        }

                        // Apply include pattern filter
                        if (finalIncludeMatcher != null && !finalIncludeMatcher.matches(file)) {
                            return FileVisitResult.CONTINUE;
                        }

                        searchInFile(file, searchPattern, ctxLines, results, matchCount, outputChars, finalIncludeMatcher);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (SKIP_DIRS.contains(dirName)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (Exception e) {
            return "Error during search: " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No matches found for pattern: " + pattern;
        }

        StringBuilder output = new StringBuilder();
        for (String line : results) {
            if (output.length() + line.length() > MAX_OUTPUT_CHARS) {
                output.append("... [output truncated at ").append(MAX_OUTPUT_CHARS).append(" chars]");
                break;
            }
            output.append(line).append("\n");
        }

        return output.toString();
    }

    /**
     * Searches for pattern matches within a single file.
     */
    private void searchInFile(Path file, Pattern searchPattern, int contextLines,
                              List<String> results, AtomicInteger matchCount,
                              AtomicInteger outputChars, PathMatcher includeMatcher) {
        // Skip if include pattern doesn't match
        if (includeMatcher != null && !includeMatcher.matches(file)) {
            return;
        }

        // Skip files that are too large (> 10MB)
        try {
            if (Files.size(file) > 10 * 1024 * 1024) {
                return;
            }
        } catch (Exception e) {
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (Exception e) {
            // Skip files that can't be read as text (binary files, etc.)
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (matchCount.get() >= MAX_MATCHES || outputChars.get() >= MAX_OUTPUT_CHARS) {
                return;
            }

            String line = lines.get(i);
            if (searchPattern.matcher(line).find()) {
                matchCount.incrementAndGet();

                StringBuilder entry = new StringBuilder();
                entry.append(file.toString()).append(":").append(i + 1).append(":").append(line);

                // Add context lines before
                if (contextLines > 0) {
                    StringBuilder ctx = new StringBuilder();
                    int start = Math.max(0, i - contextLines);
                    int end = Math.min(lines.size() - 1, i + contextLines);
                    for (int j = start; j <= end; j++) {
                        if (j == i) {
                            ctx.append(file.toString()).append(":").append(j + 1).append(":").append(lines.get(j));
                        } else {
                            ctx.append(file.toString()).append("-").append(j + 1).append("-").append(lines.get(j));
                        }
                        if (j < end) ctx.append("\n");
                    }
                    entry = new StringBuilder(ctx);
                }

                String entryStr = entry.toString();
                outputChars.addAndGet(entryStr.length() + 1);
                results.add(entryStr);
            }
        }
    }
}
