// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.kernel

import com.slte.app.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延迟三态 / 成员分类 / 主组解析 / 面板序重排 / 输入校验的纯函数单测。
 * 这些用例不需要内核、不需要真机。
 */
class DelaySemanticsTest {

    @Test
    fun `真实延迟原样返回且与是否测过无关`() {
        assertEquals(123, resolveDelay(123, hasTestRun = true))
        assertEquals(123, resolveDelay(123, hasTestRun = false))
        assertEquals(65534, resolveDelay(65534, hasTestRun = true))
    }

    @Test
    fun `0xffff 在测过时判超时、未测时判未测`() {
        assertEquals(Constants.DELAY_TIMEOUT, resolveDelay(Constants.DELAY_INVALID_MAX, hasTestRun = true))
        assertEquals(Constants.DELAY_PENDING, resolveDelay(Constants.DELAY_INVALID_MAX, hasTestRun = false))
    }

    @Test
    fun `无数据一律判未测`() {
        assertEquals(Constants.DELAY_PENDING, resolveDelay(0, hasTestRun = true))
        assertEquals(Constants.DELAY_PENDING, resolveDelay(-1, hasTestRun = false))
    }

    @Test
    fun `未测与超时必须是两个不同的值`() {
        assertTrue(Constants.DELAY_PENDING != Constants.DELAY_TIMEOUT)
        assertTrue(Constants.DELAY_PENDING < Constants.DELAY_INVALID_MAX)
    }

    @Test
    fun `成员分类由名字与 isGroup 决定`() {
        assertEquals(KernelProxyMemberKind.DIRECT, memberKindOf("DIRECT", isGroup = false))
        assertEquals(KernelProxyMemberKind.REJECT, memberKindOf("REJECT", isGroup = false))
        assertEquals(KernelProxyMemberKind.GROUP, memberKindOf("自动选择", isGroup = true))
        assertEquals(KernelProxyMemberKind.NODE, memberKindOf("香港 01", isGroup = false))
    }

    @Test
    fun `主组解析优先精确名`() {
        val groups =
            listOf(
                group("自动选择", selectable = false),
                group(PrimaryGroupName, selectable = true),
                group("其他", selectable = true),
            )
        assertEquals(PrimaryGroupName, primaryGroupOf(groups)?.name)
    }

    @Test
    fun `无精确名时回退第一个可选组`() {
        // 本地分流关闭 / 面板自定义组名的场景，N-08：不得出现「没有任何节点被选中」
        val groups =
            listOf(
                group("自动选择", selectable = false),
                group("机场节点", selectable = true),
                group("其他", selectable = true),
            )
        assertEquals("机场节点", primaryGroupOf(groups)?.name)
    }

    @Test
    fun `全无可选组时返回 null`() {
        assertTrue(primaryGroupOf(listOf(group("自动选择", selectable = false))).let { it == null })
    }

    @Test
    fun `面板序重排保持结构项在前`() {
        val members =
            listOf(
                member("日本 01"),
                member("香港 01"),
                member("新加坡 01"),
                member("自动选择", KernelProxyMemberKind.GROUP),
                member("DIRECT", KernelProxyMemberKind.DIRECT),
            )
        val order = mapOf("香港 01" to 0, "日本 01" to 1, "新加坡 01" to 2)
        val result = orderMembers(members, order)
        assertEquals(
            listOf("自动选择", "DIRECT", "香港 01", "日本 01", "新加坡 01"),
            result.map { it.name },
        )
    }

    @Test
    fun `不在面板列表的成员追加到节点段末尾且不丢成员`() {
        val members = listOf(member("provider-only"), member("香港 01"))
        val result = orderMembers(members, mapOf("香港 01" to 0))
        assertEquals(listOf("香港 01", "provider-only"), result.map { it.name })
        assertEquals(2, result.size)
    }

    @Test
    fun `面板索引为空时原样返回内核序`() {
        val members = listOf(member("b"), member("a"))
        assertEquals(members, orderMembers(members, emptyMap()))
    }

    /**
     * 面板索引用订阅名、成员名是内核名（带国旗前缀）时也要重排成功。
     *
     * 精确查表会全部落空 → 全是 Int.MAX_VALUE → 排序退化成 no-op，
     * 节点段保留内核 UTF-8 字节序（与面板顺序不一致）。
     */
    @Test
    fun `内核名带国旗前缀时仍能按面板序重排`() {
        val members = listOf(member("🇯🇵 日本 01"), member("🇭🇰 香港 01"), member("🇸🇬 新加坡 01"))
        val order = mapOf("香港 01" to 0, "日本 01" to 1, "新加坡 01" to 2)
        val result = orderMembers(members, order)
        assertEquals(listOf("🇭🇰 香港 01", "🇯🇵 日本 01", "🇸🇬 新加坡 01"), result.map { it.name })
    }

    private fun group(
        name: String,
        selectable: Boolean,
    ) = KernelProxyGroupInfo(name = name, type = "Selector", now = null, selectable = selectable, members = emptyList())

    private fun member(
        name: String,
        kind: KernelProxyMemberKind = KernelProxyMemberKind.NODE,
    ) = KernelProxyMember(name = name, isGroup = kind == KernelProxyMemberKind.GROUP, delay = null, kind = kind)
}

class SpeedTestOutcomeTest {

    @Test
    fun `空结果不得判为完成`() {
        // 回归：values.all{} 对空 Map 恒为 true，会让测速"瞬间成功"
        assertEquals(SpeedTestOutcome.EMPTY, speedTestOutcome(emptyMap(), completed = true))
        assertEquals(SpeedTestOutcome.EMPTY, speedTestOutcome(emptyMap(), completed = false))
    }

    @Test
    fun `全部未测或全部超时都算空结果`() {
        assertEquals(
            SpeedTestOutcome.EMPTY,
            speedTestOutcome(mapOf("a" to Constants.DELAY_TIMEOUT, "b" to Constants.DELAY_PENDING), completed = true),
        )
    }

    @Test
    fun `有真实值但探测未完成判部分完成`() {
        assertEquals(SpeedTestOutcome.PARTIAL, speedTestOutcome(mapOf("a" to 100), completed = false))
        assertEquals(SpeedTestOutcome.COMPLETE, speedTestOutcome(mapOf("a" to 100), completed = true))
    }

    @Test
    fun `健康检查预算随成员数增长并有上下限`() {
        assertEquals(HEALTH_CHECK_MIN_BUDGET_MS, healthCheckBudgetMs(0))
        assertEquals(HEALTH_CHECK_MIN_BUDGET_MS, healthCheckBudgetMs(10))
        // ceil(60/10) = 6 批 × 5s × 1.5 = 45s
        assertEquals(45_000L, healthCheckBudgetMs(60))
        assertEquals(HEALTH_CHECK_MAX_BUDGET_MS, healthCheckBudgetMs(1000))
        assertTrue(healthCheckBudgetMs(200) >= healthCheckBudgetMs(100))
    }

    @Test
    fun `落盘只保留真实延迟且不抹掉历史好值`() {
        assertEquals(mapOf("a" to 100), storeableDelays(null, mapOf("a" to 100, "b" to Constants.DELAY_TIMEOUT)))
        // 本轮超时不覆盖上一轮的好值
        assertEquals(mapOf("a" to 100), storeableDelays(mapOf("a" to 100), mapOf("a" to Constants.DELAY_TIMEOUT)))
        // 新值覆盖旧值
        assertEquals(mapOf("a" to 50), storeableDelays(mapOf("a" to 100), mapOf("a" to 50)))
        // 未测与超时都不落盘
        assertEquals(emptyMap<String, Int>(), storeableDelays(mapOf("a" to 0, "b" to 65535), emptyMap()))
        assertEquals(emptyMap<String, Int>(), storeableDelays(null, emptyMap()))
    }

    @Test
    fun `收敛判定_全空结果必须继续等`() {
        // 回归：空 Map 会让旧判据"瞬间完成"，26 个成员 458ms 测完全读成 999
        assertEquals(SpeedSettleAction.WAIT, speedSettleAction(emptyMap(), polls = 0))
        assertEquals(SpeedSettleAction.WAIT, speedSettleAction(emptyMap(), polls = SPEED_SETTLE_MAX_POLLS))
    }

    @Test
    fun `收敛判定_全部拿到真实延迟才算完成`() {
        assertEquals(SpeedSettleAction.DONE, speedSettleAction(mapOf("a" to 120, "b" to 30), polls = 0))
    }

    @Test
    fun `收敛判定_全超时必须等满沉降窗口才认输`() {
        val allTimeout = mapOf("a" to Constants.DELAY_TIMEOUT, "b" to Constants.DELAY_TIMEOUT)
        // 未测（0）与超时（999）都算"尚无真实延迟"
        val notTested = mapOf("a" to Constants.DELAY_PENDING, "b" to Constants.DELAY_PENDING)
        for (delays in listOf(allTimeout, notTested)) {
            assertEquals(SpeedSettleAction.WAIT, speedSettleAction(delays, polls = 0))
            assertEquals(SpeedSettleAction.WAIT, speedSettleAction(delays, polls = SPEED_SETTLE_MIN_POLLS - 1))
            assertEquals(SpeedSettleAction.GIVE_UP, speedSettleAction(delays, polls = SPEED_SETTLE_MIN_POLLS))
        }
    }

    @Test
    fun `收敛判定_部分出结果则继续等且不认输`() {
        // 只要有一个真实值，就不能判定为"节点全挂"，必须继续等其余节点
        val partial = mapOf("a" to Constants.DELAY_TIMEOUT, "b" to 200)
        assertEquals(SpeedSettleAction.WAIT, speedSettleAction(partial, polls = 0))
        assertEquals(SpeedSettleAction.WAIT, speedSettleAction(partial, polls = SPEED_SETTLE_MIN_POLLS))
        assertEquals(SpeedSettleAction.DONE, speedSettleAction(mapOf("a" to 90, "b" to 200), polls = 0))
    }

    @Test
    fun `收敛判定_内核确认本轮结束后剩余超时算终态`() {
        // 回归：29 个成员 26 个真实 + 3 个真超时，旧判据为此白等满 30s
        val delays = mapOf("a" to 120, "b" to 30, "c" to Constants.DELAY_TIMEOUT)
        assertEquals(SpeedSettleAction.WAIT, speedSettleAction(delays, polls = 0, kernelRoundDone = true))
        assertEquals(
            SpeedSettleAction.DONE,
            speedSettleAction(delays, polls = SPEED_SETTLE_CONFIRM_POLLS, kernelRoundDone = true),
        )
    }

    @Test
    fun `收敛判定_内核未确认本轮结束时仍等全部真实延迟`() {
        val delays = mapOf("a" to 120, "c" to Constants.DELAY_TIMEOUT)
        assertEquals(
            SpeedSettleAction.WAIT,
            speedSettleAction(delays, polls = SPEED_SETTLE_MAX_POLLS - 1),
        )
        assertEquals(
            SpeedSettleAction.WAIT,
            speedSettleAction(delays, polls = SPEED_SETTLE_MAX_POLLS - 1, kernelRoundDone = false),
        )
    }

    @Test
    fun `收敛判定_内核确认本轮结束但没有真实延迟时不得判完成`() {
        // 反向回归：内核异常即时返回（singledo 缓存为空 / providers 为空）时
        // 读到的是一份「一条真实延迟都没有」的快照，不得据此把整页判成超时
        val allTimeout = mapOf("a" to Constants.DELAY_TIMEOUT, "b" to Constants.DELAY_TIMEOUT)
        assertEquals(
            SpeedSettleAction.WAIT,
            speedSettleAction(allTimeout, polls = 0, kernelRoundDone = true),
        )
        assertEquals(
            SpeedSettleAction.GIVE_UP,
            speedSettleAction(allTimeout, polls = SPEED_SETTLE_MIN_POLLS, kernelRoundDone = true),
        )
    }

    @Test
    fun `收敛轮询有硬上限且沉降窗口不超过上限`() {
        assertTrue(SPEED_SETTLE_MIN_POLLS > 0)
        assertTrue(SPEED_SETTLE_MAX_POLLS > SPEED_SETTLE_MIN_POLLS)
        assertTrue(SPEED_SETTLE_CONFIRM_POLLS in 1..SPEED_SETTLE_MIN_POLLS)
    }
}

/** 节点页区块折叠集合（集合内 = 收起）。 */
class ToggleCollapsedTest {

    @Test
    fun `点击在收起与展开之间往返`() {
        val collapsed = toggleCollapsed(emptySet(), PrimaryGroupName)
        assertTrue(PrimaryGroupName in collapsed)
        val expanded = toggleCollapsed(collapsed, PrimaryGroupName)
        assertTrue(PrimaryGroupName !in expanded)
    }

    @Test
    fun `切换一个组不影响其他组`() {
        val a = toggleCollapsed(emptySet(), "A")
        val ab = toggleCollapsed(a, "B")
        assertEquals(setOf("A", "B"), ab)
        assertEquals(setOf("B"), toggleCollapsed(ab, "A"))
    }

    @Test
    fun `同名不同对象（模拟刷新重建）语义不变——key 是组名而非对象`() {
        // 刷新会重建 KernelProxyGroupInfo（now/members 变化 → data class 不相等），
        // 但折叠集合只存组名，因此状态必须保持不变
        val before = KernelProxyGroupInfo(
            name = PrimaryGroupName,
            type = "Selector",
            now = "香港 01",
            selectable = true,
            members = emptyList(),
        )
        val collapsed = toggleCollapsed(emptySet(), before.name)
        val after = KernelProxyGroupInfo(
            name = PrimaryGroupName,
            type = "Selector",
            now = "日本 01",
            selectable = true,
            members = listOf(KernelProxyMember("日本 01", false, 42)),
        )
        assertTrue("刷新重建后仍应保持收起", after.name in collapsed)
    }

    @Test
    fun `重复切换总是回到集合原状`() {
        val start = setOf("X")
        assertEquals(start, toggleCollapsed(toggleCollapsed(start, "Y"), "Y"))
    }
}

class RoutingInputValidatorTest {
    @Test
    fun `合法组名通过`() {
        assertTrue(RoutingInputValidator.isValidGroupName("我的规则"))
        assertTrue(RoutingInputValidator.isValidGroupName("My Rules 2"))
        assertTrue(RoutingInputValidator.isValidGroupName("日本🇯🇵-01"))
    }

    @Test
    fun `逗号会被规则串字段分隔符错位必须拒绝`() {
        assertFalse(RoutingInputValidator.isValidGroupName("a,b"))
        assertFalse(RoutingInputValidator.isValidGroupName("DIRECT,x"))
    }

    @Test
    fun `控制字符与首尾空格必须拒绝`() {
        assertFalse(RoutingInputValidator.isValidGroupName("a\nb"))
        assertFalse(RoutingInputValidator.isValidGroupName(" a"))
        assertFalse(RoutingInputValidator.isValidGroupName("a "))
        assertFalse(RoutingInputValidator.isValidGroupName(""))
    }

    @Test
    fun `超长与保留名必须拒绝`() {
        assertFalse(RoutingInputValidator.isValidGroupName("x".repeat(33)))
        assertFalse(RoutingInputValidator.isValidGroupName(PrimaryGroupName))
        assertFalse(RoutingInputValidator.isValidGroupName("自动选择"))
        assertFalse(RoutingInputValidator.isValidGroupName("DIRECT"))
        assertFalse(RoutingInputValidator.isValidGroupName("GLOBAL"))
    }

    @Test
    fun `直连域名形态校验`() {
        assertTrue(RoutingInputValidator.isValidRuleDomainShape("example.com"))
        assertTrue(RoutingInputValidator.isValidRuleDomainShape("a.b.example.com"))
        assertFalse(RoutingInputValidator.isValidRuleDomainShape("x,MATCH,DIRECT.example.com"))
        assertFalse(RoutingInputValidator.isValidRuleDomainShape("a..b.com"))
        assertFalse(RoutingInputValidator.isValidRuleDomainShape("-a.com"))
        assertFalse(RoutingInputValidator.isValidRuleDomainShape("a-.com"))
        assertFalse(RoutingInputValidator.isValidRuleDomainShape("http://a.com"))
    }
}

/**
 * 承载组解析：必须返回「真正含该节点的组」。
 *
 * 回归真机「选 [直]3美国 → 连接后变 [直]1德国」：旧实现在找不到承载组时回退主组名
 * （主组的成员只有子组，patch 必然失败），而调用方用 `!= null` 判断"是否已解析到
 * 承载组"，拿到主组名就立刻停止重试 → patch 主组失败 → 兜底 selectAuto 打回最快
 * 节点。因此「找不到」必须返回 null，让调用方继续等内核把子组物化。
 */
class ResolveCarryingGroupTest {

    private fun grp(
        name: String,
        selectable: Boolean,
        vararg members: String,
    ) = KernelProxyGroupInfo(
        name = name,
        type = if (selectable) "Selector" else "URLTest",
        now = null,
        selectable = selectable,
        members = members.map { KernelProxyMember(it, isGroup = false, delay = null) },
    )

    @Test
    fun `扁平订阅_主组直接含节点时返回主组`() {
        val groups = listOf(grp("niuren", true, "[直]1德国", "[直]3美国"))
        assertEquals("niuren", resolveCarryingGroup(groups, "[直]3美国"))
    }

    @Test
    fun `嵌套订阅_真实节点挂在自动选择子组时返回子组`() {
        // 真机结构：主组 niuren 的成员是子组「自动选择」，真实节点挂在子组下
        val groups =
            listOf(
                grp("niuren", true, "自动选择"),
                grp("自动选择", false, "[直]1德国", "[直]3美国"),
            )
        assertEquals("自动选择", resolveCarryingGroup(groups, "[直]3美国"))
    }

    @Test
    fun `找不到承载组时必须返回 null 而不是主组名`() {
        // 回归：旧实现末尾 `?: primary?.name` 返回"niuren"（成员只有子组）→ patch 必失败
        val groups = listOf(grp("niuren", true, "自动选择"))
        assertEquals(null, resolveCarryingGroup(groups, "[直]3美国"))
    }

    @Test
    fun `子组尚未物化时不得伪造成功`() {
        // 连接就绪瞬间内核可能只给出主组、子组成员列表还是空的
        val groups = listOf(grp("niuren", true, "自动选择"), grp("自动选择", false))
        assertEquals(null, resolveCarryingGroup(groups, "[直]3美国"))
    }

    @Test
    fun `内核节点名带国旗前缀时仍解析到承载组`() {
        // 真机关键回归：内核里的成员名是 `🇺🇸 [直]3美国`（带国旗），
        // 而面板/订阅列表里是 `[直]3美国`。精确相等永远对不上 → 解析失败 →
        // patch 不存在的组 → 兜底 selectAuto → 连接后变 [直]1德国。
        val groups =
            listOf(
                grp("niuren", true, "自动选择"),
                grp("自动选择", false, "🇩🇪 [直]1德国", "🇺🇸 [直]3美国", "🇺🇸 [CF]3美国"),
            )
        assertEquals("自动选择", resolveCarryingGroup(groups, "[直]3美国"))
    }

    @Test
    fun `组列表为空时返回 null`() {
        assertEquals(null, resolveCarryingGroup(emptyList(), "[直]3美国"))
    }
}

/** 节点页成员可见性：移除「故障转移」入口，但保留 now 命中项（防"鬼选中"）。 */
class VisibleGroupMembersTest {

    private fun auto() = KernelProxyMember(AUTO_GROUP_NAME, true, null, KernelProxyMemberKind.GROUP)

    private fun fallback() = KernelProxyMember(FALLBACK_GROUP_NAME, true, null, KernelProxyMemberKind.GROUP)

    private fun node(name: String) = KernelProxyMember(name, false, 42)

    @Test
    fun `默认入口里没有故障转移、但有自动选择`() {
        val members = listOf(auto(), fallback(), node("香港 01"))
        val visible = visibleGroupMembers(members, now = "香港 01")
        assertEquals(listOf(AUTO_GROUP_NAME, "香港 01"), visible.map { it.name })
    }

    @Test
    fun `当前出口正是故障转移时必须保留该项`() {
        // 否则卡片头部显示「故障转移」，列表里却没有任何勾选行
        val members = listOf(auto(), fallback(), node("香港 01"))
        val visible = visibleGroupMembers(members, now = FALLBACK_GROUP_NAME)
        assertEquals(listOf(AUTO_GROUP_NAME, FALLBACK_GROUP_NAME, "香港 01"), visible.map { it.name })
    }

    @Test
    fun `now 为空或不匹配时判定为移除`() {
        val members = listOf(fallback(), node("香港 01"))
        assertEquals(listOf("香港 01"), visibleGroupMembers(members, now = null).map { it.name })
        assertEquals(listOf("香港 01"), visibleGroupMembers(members, now = "日本 01").map { it.name })
    }

    @Test
    fun `不改动其余成员的相对顺序与身份`() {
        val members = listOf(node("A"), fallback(), node("B"))
        val visible = visibleGroupMembers(members, now = "B")
        assertEquals(listOf("A", "B"), visible.map { it.name })
        assertTrue("必须是原对象而不是重建的副本", visible[0] === members[0])
    }
}
