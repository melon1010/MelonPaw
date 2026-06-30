/**
 * @author melon
 */
package com.melon.core.util;

/**
 * 文本截断工具. 对应 Python agents/tools/utils.py 的 truncate_text_output.
 */
public class TextTruncateUtil {

    public static final int DEFAULT_MAX_BYTES = 50 * 1024;

    /**
     * 按字节截断文本, 保留行完整性.
     */
    public static String truncate(String text, int maxBytes, String notice) {
        if (text == null) return "";
        byte[] bytes = text.getBytes();
        if (bytes.length <= maxBytes) return text;

        // 按字节截断, 回退到最后一个换行符
        int cut = maxBytes;
        while (cut > 0 && bytes[cut - 1] != '\n') cut--;

        String truncated = new String(bytes, 0, cut > 0 ? cut : maxBytes);
        int startLine = countLines(truncated) + 1;
        int totalLines = countLines(text);

        return truncated + "\n" + notice + "\n"
                + "[Truncated: " + cut + "/" + bytes.length + " bytes, lines " + startLine + "-" + totalLines + "]";
    }

    private static int countLines(String text) {
        int count = 1;
        for (char c : text.toCharArray()) {
            if (c == '\n') count++;
        }
        return count;
    }
}
