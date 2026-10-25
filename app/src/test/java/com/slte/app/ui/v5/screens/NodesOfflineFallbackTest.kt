// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.ui.v5.screens

import com.slte.app.kernel.KernelProxyMemberKind
import com.slte.app.ui.screen.server.NodeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未连接（内核未运行）时的节点兜底名单。
 *
 * 回归背景（用户实测反馈）："首页那个连接不点，节点就全不显示"。
 * 节点行只来自内核策略组，未连接时整页只剩「暂无节点，请先更新订阅」——
 * 而订阅缓存里其实一直有节点名单（离线可用），只是没被用上。
 */
class NodesOfflineFallbackTest {

    @Test
    fun `兜底名单保留订阅顺序且透传真实延迟`() {
        val nodes =
            listOf(
                NodeItem(id = 1, name = "🇭🇰 香港 01", delay = 88),
                NodeItem(id = 2, name = "🇯🇵 日本 02"),
                NodeItem(id = 3, name = "🇺🇸 美国 03", delay = 999),
            )

        val members = offlineMembersOf(nodes)

        assertEquals(
            "顺序必须与订阅一致（用户认的是订阅里的顺序）",
            listOf("🇭🇰 香港 01", "🇯🇵 日本 02", "🇺🇸 美国 03"),
            members.map { it.name },
        )
        // 1.12.6 语义（真机反馈"未连接点测速没有延迟出来"）：兜底名单必须透传
        // data.nodes 里的真实延迟——未连接 TCP 握手测速的结果就合在那里，否则
        // 内核没起时测了也白测。没有测过（null/0）的仍是「未测」，超时 999 原样
        // 透传、由行组件渲染成「超时」（与内核路径的 999 成员同口径）。
        assertEquals("测过的节点显示真实毫秒数", 88, members[0].delay)
        assertNull("没测过的节点保持未测", members[1].delay)
        assertEquals("超时 999 原样透传（显示为超时）", 999, members[2].delay)
        assertTrue(
            "订阅里的条目都是节点，不能标成策略组",
            members.all { it.kind == KernelProxyMemberKind.NODE && !it.isGroup },
        )
    }

    @Test
    fun `没有缓存节点时兜底名单为空_页面回落到原有空态`() {
        assertTrue(offlineMembersOf(emptyList()).isEmpty())
    }
}
