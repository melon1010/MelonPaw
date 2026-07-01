package com.melon.core.util;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 系统信息工具. 提供运行时系统和 JVM 信息查询.
 * 对应 Python 的 system_info 工具函数.
 */
public final class SystemInfoUtil {

    private SystemInfoUtil() {
    }

    // ======================== JVM Info ========================

    /**
     * 获取 Java 运行时版本.
     *
     * @return Java 版本字符串, 如 "17.0.1"
     */
    public static String getJavaVersion() {
        return System.getProperty("java.version", "unknown");
    }

    /**
     * 获取 Java 运行时供应商.
     *
     * @return 供应商名称, 如 "Oracle Corporation"
     */
    public static String getJavaVendor() {
        return System.getProperty("java.vendor", "unknown");
    }

    /**
     * 获取 Java 主目录.
     */
    public static String getJavaHome() {
        return System.getProperty("java.home", "unknown");
    }

    // ======================== OS Info ========================

    /**
     * 获取操作系统名称.
     *
     * @return OS 名称, 如 "Windows 10" / "Linux" / "Mac OS X"
     */
    public static String getOSName() {
        return System.getProperty("os.name", "unknown");
    }

    /**
     * 获取操作系统版本.
     */
    public static String getOSVersion() {
        return System.getProperty("os.version", "unknown");
    }

    /**
     * 获取操作系统架构.
     *
     * @return 架构, 如 "amd64" / "aarch64"
     */
    public static String getOsArch() {
        return System.getProperty("os.arch", "unknown");
    }

    /**
     * 获取用户主目录.
     */
    public static String getUserHome() {
        return System.getProperty("user.home", ".");
    }

    /**
     * 获取当前工作目录.
     */
    public static String getWorkingDir() {
        return System.getProperty("user.dir", ".");
    }

    // ======================== Hardware Info ========================

    /**
     * 获取可用的处理器核心数.
     *
     * @return 可用处理器数
     */
    public static int getAvailableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * 获取 JVM 最大可用内存 (字节).
     */
    public static long getMaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    /**
     * 获取 JVM 已分配内存 (字节).
     */
    public static long getTotalMemory() {
        return Runtime.getRuntime().totalMemory();
    }

    /**
     * 获取 JVM 空闲内存 (字节).
     */
    public static long getFreeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    /**
     * 获取 JVM 已使用内存 (字节).
     */
    public static long getUsedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ======================== Disk Info ========================

    /**
     * 获取指定路径所在磁盘的可用空间 (字节).
     *
     * @param path 路径
     * @return 可用空间, 出错返回 -1
     */
    public static long getDiskSpace(String path) {
        if (path == null || path.isBlank()) {
            path = ".";
        }
        try {
            File file = new File(path);
            return file.getUsableSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取指定路径所在磁盘的总空间 (字节).
     *
     * @param path 路径
     * @return 总空间, 出错返回 -1
     */
    public static long getDiskTotal(String path) {
        if (path == null || path.isBlank()) {
            path = ".";
        }
        try {
            File file = new File(path);
            return file.getTotalSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取指定路径所在磁盘的已使用空间 (字节).
     *
     * @param path 路径
     * @return 已使用空间, 出错返回 -1
     */
    public static long getDiskUsed(String path) {
        long total = getDiskTotal(path);
        long free = getDiskSpace(path);
        if (total < 0 || free < 0) {
            return -1;
        }
        return total - free;
    }

    // ======================== Summary ========================

    /**
     * 获取系统信息摘要文本.
     *
     * @return 多行系统信息文本
     */
    public static String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("OS: ").append(getOSName())
                .append(" ").append(getOSVersion())
                .append(" (").append(getOsArch()).append(")\n");
        sb.append("Java: ").append(getJavaVersion())
                .append(" (").append(getJavaVendor()).append(")\n");
        sb.append("Processors: ").append(getAvailableProcessors()).append("\n");
        sb.append("Memory: used=").append(formatBytes(getUsedMemory()))
                .append(", free=").append(formatBytes(getFreeMemory()))
                .append(", max=").append(formatBytes(getMaxMemory())).append("\n");
        sb.append("Working dir: ").append(getWorkingDir()).append("\n");
        long diskFree = getDiskSpace(getWorkingDir());
        long diskTotal = getDiskTotal(getWorkingDir());
        sb.append("Disk: free=").append(formatBytes(diskFree))
                .append(", total=").append(formatBytes(diskTotal));
        return sb.toString();
    }

    // ======================== Helpers ========================

    /**
     * 格式化字节为人类可读文本.
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
