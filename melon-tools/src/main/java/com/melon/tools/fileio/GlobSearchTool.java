package com.melon.tools.fileio;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文件名搜索工具. 对应 Python file_search.py:glob_search.
 */
public class GlobSearchTool {

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "__pycache__", ".venv", "target", "build");

    @Tool(name = "glob_search", description = "Find files matching a glob pattern. Returns file paths.", readOnly = true, concurrencySafe = true)
    public String globSearch(
            @ToolParam(name = "pattern", description = "Glob pattern (e.g. **/*.java)") String pattern,
            @ToolParam(name = "path", description = "Directory to search in (optional)") String path
    ) {
        try {
            Path searchPath = path != null ? Path.of(path) : Path.of(".");
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> results = new ArrayList<>();

            Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file)) {
                        results.add(file.toString());
                    }
                    return results.size() < 200 ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) {
                return "No files found matching pattern: " + pattern;
            }
            return String.join("\n", results);
        } catch (Exception e) {
            return "Error searching files: " + e.getMessage();
        }
    }
}
