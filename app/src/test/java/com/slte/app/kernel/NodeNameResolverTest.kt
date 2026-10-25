// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeNameResolverTest {

    // --- of(): normalization ---

    @Test
    fun `全角字母数字折叠为半角小写`() {
        assertEquals("abc123", NodeNameResolver.of("ＡＢＣ１２３"))
    }

    @Test
    fun `零宽字符被剥离`() {
        assertEquals("hello", NodeNameResolver.of("hel\u200Blo"))
        assertEquals("world", NodeNameResolver.of("wor\uFEFFld"))
        assertEquals("test", NodeNameResolver.of("te\u202Est"))
    }

    @Test
    fun `前导方括号装饰被移除`() {
        assertEquals("server", NodeNameResolver.of("[tag]server"))
        assertEquals("server", NodeNameResolver.of("【标签】server"))
        assertEquals("server", NodeNameResolver.of("(note)server"))
        assertEquals("server", NodeNameResolver.of("（备注）server"))
    }

    @Test
    fun `多层前导装饰被逐层移除`() {
        assertEquals("server", NodeNameResolver.of("[a][b]server"))
        assertEquals("server", NodeNameResolver.of("【外】【内】server"))
    }

    @Test
    fun `符号与 emoji 装饰被移除`() {
        assertEquals("hk", NodeNameResolver.of("🇭🇰 hk"))
        assertEquals("node", NodeNameResolver.of("⭐ node"))
        assertEquals("jp", NodeNameResolver.of("🇯🇵jp"))
    }

    @Test
    fun `空白被完全移除`() {
        assertEquals("helloworld", NodeNameResolver.of("hello world"))
        assertEquals("abc", NodeNameResolver.of("  a\tb c "))
        assertEquals("name", NodeNameResolver.of("name\u3000"))
    }

    @Test
    fun `混合全角与装饰折叠后归一`() {
        assertEquals("server", NodeNameResolver.of("【ＴＡＧ】Ｓｅｒｖｅｒ"))
    }

    @Test
    fun `空字符串归一为空`() {
        assertEquals("", NodeNameResolver.of(""))
    }

    @Test
    fun `纯装饰输入归一为空`() {
        assertEquals("", NodeNameResolver.of("[tag]"))
        assertEquals("", NodeNameResolver.of("【标签】"))
        assertEquals("", NodeNameResolver.of("⭐⭐⭐"))
    }

    // --- protocolTag() ---

    @Test
    fun `提取协议标签`() {
        assertEquals("vless", NodeNameResolver.protocolTag("[vless]server"))
        assertEquals("vmess", NodeNameResolver.protocolTag("[vmess]server"))
        assertEquals("trojan", NodeNameResolver.protocolTag("[trojan]server"))
        assertEquals("ss", NodeNameResolver.protocolTag("[ss]server"))
        assertEquals("hy2", NodeNameResolver.protocolTag("[hy2]server"))
    }

    @Test
    fun `协议标签大小写不敏感`() {
        assertEquals("vless", NodeNameResolver.protocolTag("[VLESS]server"))
        assertEquals("vmess", NodeNameResolver.protocolTag("[VMess]server"))
    }

    @Test
    fun `无协议标签返回 null`() {
        assertNull(NodeNameResolver.protocolTag("server"))
        assertNull(NodeNameResolver.protocolTag("[unknown]server"))
    }

    @Test
    fun `协议标签带空格也能提取`() {
        assertEquals("vless", NodeNameResolver.protocolTag("  [vless]  server"))
    }

    // --- displayName() ---

    @Test
    fun `显示名移除协议前缀`() {
        assertEquals("server", NodeNameResolver.displayName("[vless]server"))
        assertEquals("server", NodeNameResolver.displayName("[VMESS]server"))
    }

    @Test
    fun `无协议前缀原样返回`() {
        assertEquals("my-server", NodeNameResolver.displayName("my-server"))
        assertEquals("server", NodeNameResolver.displayName("  server  "))
    }

    // --- resolve(): fuzzy member matching ---

    @Test
    fun `精确匹配直接返回成员`() {
        val members = listOf("server-1", "server-2")
        assertEquals("server-1", NodeNameResolver.resolve(members, "server-1"))
    }

    @Test
    fun `大小写差异通过归一匹配`() {
        val members = listOf("Server-1", "Server-2")
        assertEquals("Server-1", NodeNameResolver.resolve(members, "server-1"))
    }

    @Test
    fun `全角差异通过归一匹配`() {
        val members = listOf("Ｓｅｒｖｅｒ")
        assertEquals("Ｓｅｒｖｅｒ", NodeNameResolver.resolve(members, "Server"))
    }

    @Test
    fun `装饰差异通过归一匹配`() {
        val members = listOf("[vless]server")
        assertEquals("[vless]server", NodeNameResolver.resolve(members, "server"))
    }

    @Test
    fun `空目标返回 null`() {
        assertNull(NodeNameResolver.resolve(listOf("server"), ""))
    }

    @Test
    fun `无匹配返回 null`() {
        assertNull(NodeNameResolver.resolve(listOf("alpha", "beta"), "gamma"))
    }

    @Test
    fun `多个归一后等价的成员返回 null 避免歧义`() {
        val members = listOf("[vless]server", "[vmess]server")
        assertNull(NodeNameResolver.resolve(members, "server"))
    }

    @Test
    fun `空成员列表返回 null`() {
        assertNull(NodeNameResolver.resolve(emptyList(), "server"))
    }

    // --- lightKey(): 保留前导标签，只剥装饰（去歧义专用口径） ---

    @Test
    fun `lightKey 剥装饰但保留前导标签，of 则连标签一起剥`() {
        // 真机内核名带国旗前缀：`🇺🇸 [直]3美国`
        assertEquals("[直]3美国", NodeNameResolver.lightKey("🇺🇸 [直]3美国"))
        assertEquals("3美国", NodeNameResolver.of("🇺🇸 [直]3美国"))
        assertEquals("[cf]3美国", NodeNameResolver.lightKey("🇺🇸 [CF]3美国"))
    }

    @Test
    fun `国旗前缀差异要能匹配上`() {
        // 回归真机：内核里的节点名带国旗 emoji，面板/订阅列表里没有；
        // 精确相等对不上 → 旧逻辑判「组成员不存在」
        val members = listOf("🇩🇪 [直]1德国", "🇺🇸 [直]3美国")
        assertEquals("🇺🇸 [直]3美国", NodeNameResolver.resolve(members, "[直]3美国"))
    }

    @Test
    fun `只差前导标签的两个节点不得歧义成 null`() {
        // 回归真机「选 [直]3美国 连接后变 [直]1德国」：of() 把 [直]/[CF] 都剥掉，
        // 两者折叠成同一个 key，singleOrNull 因 2 个命中返回 null。
        // lightKey 保留标签后必须各自唯一命中。
        val members = listOf("🇩🇪 [直]1德国", "🇺🇸 [直]3美国", "🇺🇸 [CF]3美国")
        assertEquals("🇺🇸 [直]3美国", NodeNameResolver.resolve(members, "[直]3美国"))
        assertEquals("🇺🇸 [CF]3美国", NodeNameResolver.resolve(members, "[CF]3美国"))
    }

    @Test
    fun `确实无法区分时才返回 null`() {
        // 连"保留标签"之后都一模一样 → 真歧义，保持返回 null
        assertNull(NodeNameResolver.resolve(listOf("🇩🇪 3美国", "🇺🇸 3美国"), "3美国"))
    }

    /**
     * 成员名**重复**不能被当成歧义：本地分流下每个生成组都是 include-all，
     * 摊平多个组的成员后同一节点会重复 N 次（真实调用：
     * ServerViewModel.probeFailedNodes 的 `_proxyGroups.flatMap { it.members }`）。
     * 去重前 `singleOrNull()` 见 N>1 一律返回 null，解析静默失败。
     */
    @Test
    fun `成员名重复时不误判为歧义`() {
        val members = listOf("🇩🇪 [直]1德国", "🇩🇪 [直]1德国", "🇺🇸 [直]3美国", "🇺🇸 [直]3美国")
        assertEquals("🇩🇪 [直]1德国", NodeNameResolver.resolve(members, "[直]1德国"))
        assertEquals("🇺🇸 [直]3美国", NodeNameResolver.resolve(members, "[直]3美国"))
    }
}
