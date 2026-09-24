package com.mewchat.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 哈希工具。
 *
 * @author MewChat
 */
public final class HashUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtils() {
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要（小写）。
     *
     * <p>用于低置信度问题池的去重聚合：同一个问题的不同问法
     * （大小写、多余空格）归一化后应当落到同一个摘要上。
     *
     * @param text 原始文本
     * @return 64 位十六进制字符串
     */
    public static String sha256Hex(String text) {
        if (text == null) {
            text = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；真走到了也说明环境异常，直接暴露
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /**
     * 问题文本归一化，用于去重前的预处理。
     *
     * <p>处理内容：去首尾空白、转小写、去掉所有空白字符与常见中英文标点。
     * 目的不是语义归一（"怎么退货"和"如何退换"仍算两个问题），
     * 而是消除"同一个问题因排版差异被重复计数"这种噪音。
     *
     * @param question 原始问题
     * @return 归一化后的文本
     */
    public static String normalizeQuestion(String question) {
        if (question == null) {
            return "";
        }
        return question.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]", "");
    }
}
