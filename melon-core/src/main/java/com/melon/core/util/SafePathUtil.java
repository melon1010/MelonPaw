/**
 * @author melon
 */
package com.melon.core.util;

import java.nio.file.Path;

/**
 * 路径安全工具. 防目录穿越.
 */
public class SafePathUtil {

    /**
     * 安全解析相对路径, 防止目录穿越.
     */
    public static Path resolveSafe(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new SecurityException("Path traversal detected: " + relative);
        }
        return resolved;
    }

    /**
     * 检查路径是否在目录内.
     */
    public static boolean isWithinDir(Path path, Path dir) {
        return path.normalize().startsWith(dir.normalize());
    }
}
