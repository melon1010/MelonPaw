package com.melon.core.agent.util;

import com.melon.core.util.TextTruncateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件处理工具. 对应 Python agents/utils.py 的文件读取辅助方法.
 * <p>
 * 提供编码检测读取文件 (UTF-8 / GBK / ISO-8859-1), 并截断超长内容.
 */
public final class FileHandlingUtil {

    private static final Logger log = LoggerFactory.getLogger(FileHandlingUtil.class);

    /** 默认最大读取字数 (50KB). */
    public static final int DEFAULT_MAX_BYTES = 50 * 1024;

    private FileHandlingUtil() {
    }

    /**
     * 安全读取文件: 检测编码, 读取内容, 截断超长内容.
     *
     * @param filePath 文件路径
     * @return 文件内容字符串, 失败返回错误信息
     */
    public static String safeReadFile(String filePath) {
        return safeReadFile(filePath, DEFAULT_MAX_BYTES);
    }

    /**
     * 安全读取文件: 检测编码, 读取内容, 截断超长内容.
     *
     * @param filePath  文件路径
     * @param maxBytes  最大读取字节数
     * @return 文件内容字符串, 失败返回错误信息
     */
    public static String safeReadFile(String filePath, int maxBytes) {
        if (filePath == null || filePath.isBlank()) {
            return "[Error: file path is empty]";
        }

        Path path;
        try {
            path = Paths.get(filePath);
        } catch (Exception e) {
            return "[Error: invalid path: " + filePath + "]";
        }

        if (!Files.exists(path)) {
            return "[Error: file not found: " + filePath + "]";
        }

        if (!Files.isRegularFile(path)) {
            return "[Error: not a regular file: " + filePath + "]";
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return "[Empty file]";
            }

            // 截断超长内容
            if (bytes.length > maxBytes) {
                byte[] truncated = new byte[maxBytes];
                System.arraycopy(bytes, 0, truncated, 0, maxBytes);
                String content = new String(truncated, detectEncoding(truncated));
                return TextTruncateUtil.truncate(content, maxBytes,
                        "\n... [File truncated]");
            }

            Charset encoding = detectEncoding(bytes);
            String content = new String(bytes, encoding);
            log.debug("Read file: {}, encoding={}, size={}bytes", filePath, encoding, bytes.length);
            return content;
        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return "[Error reading file: " + e.getMessage() + "]";
        }
    }

    /**
     * 检测字节数组的编码.
     * <p>
     * 检测顺序:
     * <ol>
     *   <li>BOM 检测 (UTF-8 BOM)</li>
     *   <li>UTF-8 有效性检测</li>
     *   <li>GBK 可解码检测</li>
     *   <li>回退到 ISO-8859-1 (永不失败)</li>
     * </ol>
     *
     * @param bytes 文件字节
     * @return 检测到的字符集
     */
    public static Charset detectEncoding(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Charset.forName("UTF-8");
        }

        // 1. BOM 检测
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return Charset.forName("UTF-8");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return Charset.forName("UTF-16LE");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return Charset.forName("UTF-16BE");
        }

        // 2. UTF-8 有效性检测
        if (isValidUTF8(bytes)) {
            return Charset.forName("UTF-8");
        }

        // 3. GBK 检测 (Windows 中文环境常见)
        if (isLikelyGBK(bytes)) {
            return Charset.forName("GBK");
        }

        // 4. 回退
        return Charset.forName("ISO-8859-1");
    }

    /**
     * 检查字节数组是否为有效的 UTF-8.
     */
    private static boolean isValidUTF8(byte[] bytes) {
        try {
            String decoded = new String(bytes, "UTF-8");
            // 重新编码验证: 如果 round-trip 一致则是有效 UTF-8
            byte[] reencoded = decoded.getBytes("UTF-8");
            if (reencoded.length != bytes.length) {
                return false;
            }
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] != reencoded[i]) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 粗略检查是否可能是 GBK 编码.
     */
    private static boolean isLikelyGBK(byte[] bytes) {
        try {
            String decoded = new String(bytes, "GBK");
            // 如果解码后包含大量可打印中文字符, 则可能是 GBK
            int chineseCount = 0;
            int total = Math.min(decoded.length(), 1000);
            for (int i = 0; i < total; i++) {
                char c = decoded.charAt(i);
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                    chineseCount++;
                }
            }
            // 如果有中文字符且没有替换字符, 认为是 GBK
            return chineseCount > 0 && !decoded.contains("\uFFFD");
        } catch (Exception e) {
            return false;
        }
    }
}
