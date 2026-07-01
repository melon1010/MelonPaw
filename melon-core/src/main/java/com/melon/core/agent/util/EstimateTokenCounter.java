package com.melon.core.agent.util;

/**
 * 估算 Token 计数器. 对应 Python agents/utils.py 的 estimate_token_count.
 * <p>
 * 粗略估算文本的 token 数量, 无需加载 tokenizer:
 * <ul>
 *   <li>英文: 字符数 / 4</li>
 *   <li>中文: 字符数 / 2 (中文字符通常占 1-2 个 token)</li>
 * </ul>
 */
public final class EstimateTokenCounter {

    private EstimateTokenCounter() {
    }

    /**
     * 估算文本的 token 数量.
     * <p>
     * 区分中文字符和英文/数字字符, 分别使用不同的估算比例.
     *
     * @param text 输入文本
     * @return 估算的 token 数量
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isSupplementaryCodePoint(c)) {
                i++; // skip surrogate pair
                otherChars++;
            } else if (isChinese(c)) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }

        // 中文: ~2 字符/token -> 字符数 / 2
        // 英文/其他: ~4 字符/token -> 字符数 / 4
        int estimate = (chineseChars + 1) / 2 + (otherChars + 3) / 4;
        return estimate;
    }

    /**
     * 估算多条文本的总 token 数量.
     */
    public static int countTokens(String... texts) {
        int total = 0;
        if (texts != null) {
            for (String text : texts) {
                total += countTokens(text);
            }
        }
        return total;
    }

    /**
     * 判断字符是否为中文字符.
     */
    private static boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
