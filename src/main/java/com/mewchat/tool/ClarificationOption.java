package com.mewchat.tool;

/**
 * 澄清追问时提供给用户的候选项。
 *
 * <p>存在意义：用户被问"请提供订单号"时，往往既记不住订单号、也不愿意去翻订单页。
 * 直接把他自己的订单列出来让他挑，追问就从"考记性"变成了"点一下"。
 *
 * @param value 候选项对应的参数值（如订单号）。用户选中后，这个值会被填入缺失的参数
 * @param label 展示给用户的文案，应当让人一眼能认出是哪个订单
 * @author MewChat
 */
public record ClarificationOption(String value, String label) {
}
