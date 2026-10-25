// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.kernel

internal object NodeNameResolver {

    private const val FULL_WIDTH_FIRST = 0xFF01
    private const val FULL_WIDTH_LAST = 0xFF5E
    private const val FULL_WIDTH_OFFSET = 0xFEE0
    private const val MAX_STRIP_PASSES = 4

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")

    private val LEADING_DECORATION =
        Regex("^\\s*(?:\\[[^\\[\\]]*\\]|【[^【】]*】|\\([^()]*\\)|（[^（）]*）)\\s*")

    private val PROTOCOL_TAG_PREFIX =
        Regex(
            "^\\s*\\[(?:vless|vmess|trojan|ss|hy|hy2|tuic|anytls|socks)\\]\\s*",
            RegexOption.IGNORE_CASE,
        )

    private val SYMBOL_DECORATION = Regex(
        "[\\u2190-\\u21FF\\u2300-\\u23FF\\u25A0-\\u2775\\u27A0-\\u27BF" +
            "\\u2B00-\\u2BFF\\u3000-\\u303F\\uFE00-\\uFE0F" +
            "\\x{1F000}-\\x{1F0FF}\\x{1F1E6}-\\x{1FAFF}]",
    )

    private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")

    fun of(name: String): String {
        var body = foldWidth(ZERO_WIDTH.replace(name, ""))
        var passes = 0
        while (passes < MAX_STRIP_PASSES) {
            val next = foldWidth(SYMBOL_DECORATION.replace(stripLeadingDecoration(body), ""))
            if (next == body) break
            body = next
            passes++
        }
        return WHITESPACE.replace(body, "").lowercase()
    }

    /**
     * 在成员名里解析目标名：**由严格到宽松逐级放宽**。
     *
     * 渐进放宽是必须的。标准订阅给内核的节点名带国旗 emoji 前缀（内核里是
     * `🇩🇪 [直]1德国`），而面板/订阅列表里是 `[直]1德国`，两边必须能对上；但
     * 同一份订阅里往往同时存在 `[直]3美国` 与 `[CF]3美国` 这种只差前导标签的
     * 节点——[of] 会把前导 `[...]` 一起剥掉，两者折叠成同一个 key，
     * `.singleOrNull()` 因歧义返回 null，用户看到的就是「组成员不存在」
     * （真机：选 `[直]3美国`，连接后被打回 `[直]1德国`）。
     *
     * 因此先按「只去掉装饰符号、保留标签」比对，唯一命中即采纳——这样既吃下
     * 国旗前缀，又不会把 `[直]` 和 `[CF]` 混为一谈；只有这条也定不下来时，
     * 才放宽到连标签一起剥（全角括号 / 异体标签等老配置的最后兜底）。
     */
    fun resolve(
        members: List<String>,
        target: String,
    ): String? {
        // 成员名必须先去重：本地分流下每个生成组都是 include-all（成员=全部节点），
        // 把多个组的成员摊平后同一节点会重复 N 次，而下面的 singleOrNull() 在
        // "命中数 > 1" 时一律返回 null —— 于是解析静默失败（离线复核拿不到内核名、
        // 承载组解析不到）。去重后"重复"不再被误判成"歧义"。
        val unique = members.distinct()
        unique.firstOrNull { it == target }?.let { return it }

        // 只剥离装饰符号，保留 [直]/[CF] 这类标签：唯一命中即返回，避免剥标签引入歧义
        val decorated = lightKey(target)
        if (decorated.isNotEmpty()) {
            unique.filter { lightKey(it) == decorated }.singleOrNull()?.let { return it }
        }

        val key = of(target)
        if (key.isEmpty()) return null

        return unique.filter { of(it) == key }.singleOrNull()
    }

    /**
     * 只剥离装饰符号（零宽 / emoji / 国旗 / 空白 / 全角），**保留**前导标签。
     *
     * 与 [of] 的唯一区别就是不动 `[直]`、`[CF]` 这类标签——它们是区分不同节点的
     * 有效信息，剥掉会把一批节点折叠成同一个 key（见 [resolve] 的说明）。
     */
    fun lightKey(name: String): String {
        val noZero = ZERO_WIDTH.replace(name, "")
        val noSymbol = SYMBOL_DECORATION.replace(noZero, "")
        return WHITESPACE.replace(foldWidth(noSymbol), "").lowercase()
    }

    fun protocolTag(name: String): String? = PROTOCOL_TAG_PREFIX
        .find(name)
        ?.value
        ?.trim()
        ?.removeSurrounding("[", "]")
        ?.lowercase()

    fun displayName(name: String): String = PROTOCOL_TAG_PREFIX.replace(name, "").trim()

    private fun stripLeadingDecoration(name: String): String {
        var body = name
        while (true) {
            val next = LEADING_DECORATION.replaceFirst(body, "")
            if (next == body) return body
            body = next
        }
    }

    private fun foldWidth(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEach { char ->
            builder.append(
                when {
                    char == '\u3000' -> ' '
                    char.code in FULL_WIDTH_FIRST..FULL_WIDTH_LAST -> (char.code - FULL_WIDTH_OFFSET).toChar()
                    else -> char
                },
            )
        }
        return builder.toString()
    }
}
