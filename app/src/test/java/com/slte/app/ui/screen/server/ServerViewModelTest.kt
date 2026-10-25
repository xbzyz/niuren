// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.ui.screen.server

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.service.remote.IClashManager
import com.slte.app.R
import com.slte.app.data.local.InMemoryPreferences
import com.slte.app.data.local.NodesSectionStore
import com.slte.app.data.local.SessionStore
import com.slte.app.data.repository.ServerRepository
import com.slte.app.data.repository.SubscribeRepository
import com.slte.app.domain.model.ServerNode
import com.slte.app.domain.model.ServerType
import com.slte.app.domain.model.SubscribeInfo
import com.slte.app.domain.model.User
import com.slte.app.kernel.KernelManager
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.PRIMARY_SECTION_KEY
import com.slte.app.kernel.PrimaryGroupName
import com.slte.app.support.MainDispatcherRule
import com.slte.app.support.stubKernelBridge
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val subscribeRepository = mockk<SubscribeRepository>(relaxed = true)
    private val kernelProxy = mockk<KernelProxy>(relaxed = true)
    private val kernelManager = mockk<KernelManager>(relaxed = true)

    /** 折叠态持久化存储（真实实现 + 内存偏好），用于验证跨冷启动恢复。 */
    private val nodesSectionStore = NodesSectionStore(InMemoryPreferences())

    /**
     * `profileLoaded` 必须桩成真实 `MutableStateFlow`：relaxed mock 返回的 StateFlow
     * 一 collect 就抛 `KotlinNothingValueException`（VM 的 init 里会订阅它）。
     */
    private val profileLoadedFlow = MutableStateFlow(0)

    private fun viewModel(
        sessionStore: SessionStore = SessionStore(InMemoryPreferences()),
    ): ServerViewModel {
        kernelProxy.stubKernelBridge()
        every { subscribeRepository.getCachedSubscribeInfo() } returns null
        every { kernelManager.profileLoaded } returns profileLoadedFlow
        // connected 必须给真流：relaxed mock 的 StateFlow 一 collect 就抛 KotlinNothingValueException
        //（VM init 里订阅 connected 维护 pendingNode）
        every { kernelManager.connected } returns MutableStateFlow(false)
        return ServerViewModel(
            serverRepository,
            subscribeRepository,
            kernelProxy,
            kernelManager,
            nodesSectionStore,
            sessionStore,
        )
    }

    private fun node(
        name: String,
        id: Int = 1,
    ) = ServerNode(id = id, name = name, type = ServerType.VMESS, host = "h.example.com", port = 443)

    @Test
    fun `加载成功后填充节点并标注国家码`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns
            Result.success(listOf(node("香港01", 1), node("美国01", 2), node("香港01", 3)))
        val vm = viewModel()

        vm.loadNodes(force = true)
        advanceUntilIdle()

        val nodes = vm.data.value.nodes
        assertEquals("同名节点应去重", 2, nodes.size)
        assertEquals("HK", nodes.first { it.name == "香港01" }.countryCode)
        assertEquals("US", nodes.first { it.name == "美国01" }.countryCode)
        assertTrue(!vm.data.value.isLoading)
    }

    @Test
    fun `加载失败提示节点错误`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.failure(java.io.IOException("boom"))
        val vm = viewModel()

        vm.retry()
        advanceUntilIdle()

        assertEquals(R.string.error_server_load, vm.errorMessageRes.value)
        assertTrue(!vm.data.value.isLoading)
    }

    @Test
    fun `选中普通节点调用内核主组选择并更新选中项`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.success(listOf(node("香港01", 1)))
        val vm = viewModel()
        vm.loadNodes(force = true)
        advanceUntilIdle()

        val target = vm.data.value.nodes.first()
        vm.selectNode(target.id)
        advanceUntilIdle()

        assertEquals(target.id, vm.data.value.selectedNodeId)
    }

    /**
     * 内核侧选择失败必须给出可见反馈：旧实现丢弃了内核返回的 Boolean，
     * 用户点了没反应。这里用"内核未就绪（clash()=null）"制造失败。
     * 注意语义边界：**VPN 连接中**失败才是真故障要报错；未连接失败会降级为
     * 记住选择（见下方「未连接时patch失败降级为记住选择」）。
     */
    @Test
    fun `主组选择失败时给出可见提示`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.success(listOf(node("香港01", 1)))
        val vm = viewModel()
        // clashAlive 是顶层挂起扩展（静态分派，mockk 桩不到），走真实实现 manager.clash() != null。
        // viewModel() 里 stubKernelBridge(ready=false) 把 clash() 桩成 null → clashAlive=false →
        // 会提前退到"记住 pending"分支而不写 errorMessageRes。这里换成非空 clash 走真实下发分支。
        val aliveManager = mockk<KernelManager>(relaxed = true)
        every { aliveManager.clash() } returns mockk(relaxed = true)
        every { kernelProxy.manager } returns aliveManager
        // 模拟"连接中"：此状态下 patch 失败才是真故障，必须提示
        every { kernelManager.connected } returns MutableStateFlow(true)
        vm.loadNodes(force = true)
        advanceUntilIdle()

        vm.selectPrimary(vm.data.value.nodes.first().name)
        advanceUntilIdle()

        assertEquals(R.string.proxy_group_select_failed, vm.errorMessageRes.value)
    }

    /**
     * 回归（首页"选不了节点"根因）：进首页的瞬间 `_proxyGroups` 尚未加载
     * （DashboardPageContent 直到本轮才补拉），`selectPrimaryMember` 必须先按需
     * 补拉组列表、取真实主组名（如 "niuren"）再下发，不能回退字面量 PrimaryGroupName
     * （"🚀 节点选择"）——否则 patch 到不存在的组、选择永远不生效（真机反馈"选不了"）。
     */
    @Test
    fun `组列表未加载时也按需取真实主组名再下发选择`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()

        // 内核活着 + 可读策略组，主组真实名为 "niuren"（非字面量 PrimaryGroupName）。
        // 不调 loadProxyGroups()：模拟首页进页瞬间、组列表尚未就绪时用户抢先点选，
        // 此时 `_proxyGroups` 为空，selectPrimaryMember 必须按需补拉再下发。
        kernelProxy.stubKernelBridge()
        val clash = mockk<IClashManager>(relaxed = true)
        val proxies =
            listOf(Proxy(name = "香港01", title = "香港01", subtitle = "", type = "vmess", delay = 42, isGroup = false))
        every { clash.queryProxyGroupNames(any()) } returns listOf("niuren")
        every { clash.queryProxyGroup(any(), any()) } returns
            ProxyGroup(type = "Selector", proxies = proxies, now = "香港01")
        val manager = mockk<KernelManager>(relaxed = true)
        every { manager.clash() } returns clash
        every { kernelProxy.manager } returns manager

        assertTrue("前置：组列表应尚未加载", vm.proxyGroups.value.isEmpty())

        vm.selectPrimary("香港01")
        advanceUntilIdle()

        // 必须用真实主组名 "niuren" 下发（patchSelector 首参即组名），而非字面量 "🚀 节点选择"
        verify { clash.patchSelector("niuren", "香港01") }
        assertNull("选择成功不应写错误消息", vm.errorMessageRes.value)
    }

    /**
     * 回归（真机截图"未连接选节点弹「切换失败」"）：内核服务活着（clashAlive=true）
     * 但里面查不到任何策略组——VPN 未连接时 profile 未加载的典型现场。用户点节点
     * 只想"先选好再连接"，必须降级为记住选择（连接就绪后自动应用），而不是报
     * 「切换失败」把入口堵死。
     */
    @Test
    fun `未连接时patch失败降级为记住选择`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        coEvery { serverRepository.fetchServers(any()) } returns Result.success(listOf(node("香港01", 1)))
        // viewModel() 之后再挂内核桩：空组名列表 = 内核活着但查不到任何组
        // （VPN 未连接时 profile 未加载的典型现场）→ patch 必失败
        stubGroupNames(emptyList())
        vm.loadNodes(force = true)
        advanceUntilIdle()

        vm.selectPrimary("香港01")
        advanceUntilIdle()

        assertEquals("未连接 patch 失败应记住所选节点", "香港01", vm.pendingNode.value)
        assertNull("降级成功不应报切换失败", vm.errorMessageRes.value)
        assertEquals(R.string.node_selection_pending, vm.speedTestTipRes.value)
    }

    /**
     * 回归（真机截图"选的是 [直]3美国，连接后自动变成 [直]1德国"）：
     * 连接中手动选择成功也必须持久化 pending（= 最近一次手动选择），连接就绪后
     * MainViewModel 才能重放恢复并跳过自动测速的 selectAuto——否则每次连接都被
     * 拉回最快节点。
     */
    @Test
    fun `连接中选择成功持久化最近手动选择`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.success(listOf(node("香港01", 1)))
        val sessionStore = SessionStore(InMemoryPreferences())
        val vm = viewModel(sessionStore)
        // 连接中：内核活着 + 主组可读可 patch（stubClashGroups 置 connected=true、回读 now="香港01"）
        stubClashGroups(members = listOf("香港01" to 42), now = "香港01")
        vm.loadNodes(force = true)
        advanceUntilIdle()

        vm.selectPrimary("香港01")
        advanceUntilIdle()

        assertEquals("连接中选择成功应持久化最近手动选择", "香港01", sessionStore.getPendingNode())
        assertNull("连接中选择成功不应报错", vm.errorMessageRes.value)
        // 连接态下成功落地要发自增信号，首页据此回读出口刷新节点行/勾选
        assertEquals("连接中选择成功应触发出口刷新信号", 1, vm.selectionAppliedTick.value)
    }

    @Test
    fun `选中自动选择走专用内核入口`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()

        vm.selectNode(0)
        advanceUntilIdle()

        assertEquals(0, vm.data.value.selectedNodeId)
    }

    @Test
    fun `测速中状态标记进行中，重复触发不叠加`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()

        vm.startSpeedTest()
        assertTrue("测速应标记进行中", vm.isTestingAll.value)

        vm.startSpeedTest()
        assertTrue("重复触发不应改变状态", vm.isTestingAll.value)

        advanceUntilIdle()
        assertTrue("测速结束后应复位", !vm.isTestingAll.value)
    }

    @Test
    fun `全组测速完成后给出完成提示并刷新策略组延迟`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubClashGroups(listOf("香港01" to 42, "香港02" to 88), now = "香港01")

        vm.startSpeedTest()
        advanceUntilIdle()

        assertEquals(R.string.server_speed_test_done, vm.speedTestTipRes.value)
        assertTrue("测速结束应复位", !vm.isTestingAll.value)
        val members = vm.proxyGroups.value.first().members.associate { it.name to it.delay }
        assertEquals("策略组成员应带出内核实时延迟", 42, members["香港01"])

        vm.consumeSpeedTestTip()
        assertNull(vm.speedTestTipRes.value)
    }

    @Test
    fun `测速拿不到延迟时提示失败而非静默`() = runTest(mainRule.dispatcher) {
        // 内核未就绪 → 测速结果为空，此前这种失败是静默的
        val vm = viewModel()

        vm.startSpeedTest()
        advanceUntilIdle()

        assertEquals(R.string.server_speed_test_failed, vm.speedTestTipRes.value)
        assertTrue("测速结束应复位", !vm.isTestingAll.value)
    }

    /**
     * 回归（真机录屏）：点了测速一秒就结束、延迟全是未测。
     *
     * 1.12.6 起 startSpeedTest 按 clashAlive 分流：内核进程活着走内核真测速
     * （经代理协议，VPN 未 RUNNING 也可用）；只有内核进程都没起才退回 TCP 握手测速。
     * 本用例覆盖 TCP 兜底链路：失败节点必须显式写 999（超时），不能停在「未测」。
     * 用 127.0.0.1:1（必然拒绝连接）做确定性失败现场。
     */
    @Test
    fun `未连接TCP测速失败节点记超时并回填节点列表`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns
            Result.success(
                listOf(
                    ServerNode(id = 1, name = "香港01", type = ServerType.VMESS, host = "127.0.0.1", port = 1),
                ),
            )
        val vm = viewModel()
        // viewModel() 的默认桩 clash()=null → clashAlive=false → 走 TCP 兜底分支
        vm.ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(mainRule.dispatcher.scheduler)
        vm.loadNodes(force = true)
        advanceUntilIdle()

        vm.startSpeedTest()
        advanceUntilIdle()

        assertEquals(
            "TCP 握手失败必须写回超时（999），不得停在未测",
            com.slte.app.utils.Constants.DELAY_TIMEOUT,
            vm.data.value.nodes.first { it.name == "香港01" }.delay,
        )
        assertEquals("全部超时按失败提示", R.string.server_speed_test_failed, vm.speedTestTipRes.value)
    }

    /** 内核服务进程活着（VPN 未 RUNNING 也算）：测速必须走内核真延迟分支而不是 TCP 兜底。 */
    @Test
    fun `内核活着时测速走内核真延迟分支`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubClashGroups(listOf("香港01" to 42), now = "香港01")
        // stubClashGroups 把 manager.clash() 桩成非空 → clashAlive=true → 内核分支；
        // 节点列表为空也不影响：关键是不发起 TCP 握手（host 不可达时会写 999）
        vm.startSpeedTest()
        advanceUntilIdle()

        assertEquals(R.string.server_speed_test_done, vm.speedTestTipRes.value)
    }

    /**
     * 未连接 TCP 测速的成功现场：Gradle 测试沙箱不出网（本地端口握手也被拒），
     * 所以直接验证回填函数——握手成功的毫秒数要走同一条 `mergeDelaysIntoGroups`
     * 路径写进组成员（端到端失败链路已由上一条用例覆盖）。
     */
    @Test
    fun `未连接TCP测速成功时回填真实延迟`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupMembers("香港01", delay = 0)
        vm.loadProxyGroups()
        advanceUntilIdle()

        vm.mergeDelaysIntoGroups(mapOf("香港01" to 42, "不存在节点" to 88))

        val members = vm.proxyGroups.value.first().members.associate { it.name to it.delay }
        assertEquals("握手成功的节点必须写回真实毫秒数", 42, members["香港01"])
    }

    /** 节点列表侧的合并口径：超时命中写 999、未命中保持原样（含「未测」）。 */
    @Test
    fun `节点列表合并超时写999未命中保持原样`() {
        val vm = viewModel()
        val nodes =
            listOf(
                NodeItem(id = 1, name = "香港01", countryCode = "HK", type = "vmess", host = "h", port = 1),
                NodeItem(id = 2, name = "美国01", countryCode = "US", type = "vmess", host = "h", port = 2, delay = 33),
            )
        val merged = vm.mergeDelays(nodes, mapOf("香港01" to com.slte.app.utils.Constants.DELAY_TIMEOUT))
        assertEquals(com.slte.app.utils.Constants.DELAY_TIMEOUT, merged.first { it.name == "香港01" }.delay)
        assertEquals("未命中节点保持原延迟", 33, merged.first { it.name == "美国01" }.delay)
    }

    @Test
    fun `策略组加载时用测速缓存回填未测出的延迟`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubClashGroups(listOf("香港01" to 0, "香港02" to 77), now = "香港01")
        every { kernelProxy.speedResultStore.getSpeedResults() } returns mapOf("香港01" to 32)

        vm.loadProxyGroups()
        advanceUntilIdle()

        val members = vm.proxyGroups.value.first().members.associate { it.name to it.delay }
        assertEquals("内核未测出的成员回填缓存", 32, members["香港01"])
        assertEquals("内核已测出的成员保留实时值", 77, members["香港02"])
    }

    @Test
    fun `无套餐时测速与更新订阅弹提示而非静默`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        every { subscribeRepository.getCachedSubscribeInfo() } returns
            SubscribeInfo(planName = "", transferEnable = 0L, usedTraffic = 0L, expiredAt = 0L)

        vm.startSpeedTest()
        assertEquals(R.string.dashboard_no_plan_tip, vm.errorMessageRes.value)
        assertTrue("无套餐不应进入测速态", !vm.isTestingAll.value)

        vm.dismissError()
        vm.updateSubscription()
        assertEquals(R.string.dashboard_no_plan_tip, vm.errorMessageRes.value)
    }

    @Test
    fun `策略组内切换失败时提示错误`() = runTest(mainRule.dispatcher) {
        // 未桩化内核时 selectInGroup 扩展函数实际执行后 clash==null，safe 返回 false，触发失败路径
        val vm = viewModel()

        vm.selectInGroup(GROUP_NAME, "香港01")
        advanceUntilIdle()

        assertEquals(R.string.proxy_group_select_failed, vm.errorMessageRes.value)
    }

    @Test
    fun `购买后刷新节点成功填充列表`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.success(listOf(node("香港01", 1)))
        val vm = viewModel()

        vm.refreshNodesForPurchase()
        advanceUntilIdle()

        assertEquals(1, vm.data.value.nodes.size)
        assertNull(vm.errorMessageRes.value)
    }

    @Test
    fun `购买后刷新节点失败提示错误`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.failure(java.io.IOException("boom"))
        val vm = viewModel()

        vm.refreshNodesForPurchase()
        advanceUntilIdle()

        assertEquals(R.string.error_server_load, vm.errorMessageRes.value)
    }

    // region 节点页分组折叠态（问题1：折叠态留不住 / 问题2：应默认折叠）

    @Test
    fun `首次加载后所有区块默认展开（含主组卡与分流组）`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME))

        vm.loadProxyGroups()
        advanceUntilIdle()

        val collapsed = vm.collapsedSections.value
        assertTrue("主组卡应默认展开", PRIMARY_SECTION_KEY !in collapsed)
        assertTrue("分流组应默认展开", GROUP_NAME !in collapsed)
    }

    /**
     * 默认展开（niuren 定制）：内核可用后**不得**写任何折叠键——旧版"首见即收起"
     * 把测速结果藏进折叠卡里，用户表现为"点了测速看不到延迟"。
     */
    @Test
    fun `首次快照为空不影响默认展开，组到达后仍是全展开`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupNames(emptyList())
        vm.loadProxyGroups()
        advanceUntilIdle()
        assertTrue("空快照不应写入任何折叠键", vm.collapsedSections.value.isEmpty())

        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME))
        vm.loadProxyGroups()
        advanceUntilIdle()

        assertTrue("默认全展开：主组卡不得收起", PRIMARY_SECTION_KEY !in vm.collapsedSections.value)
        assertTrue("默认全展开：分流组不得收起", GROUP_NAME !in vm.collapsedSections.value)
    }

    /**
     * 问题1 的根因回归：`proxyGroups()` 逐组 `runCatching`（KernelProxyGroup.kt），内核重载或
     * 瞬时失败会让某个组从某次快照里消失。旧实现按"当前快照里恰好有哪些组"裁剪折叠集合
     * （`collapsed intersect names`），该组一瞬消失就把用户的折叠态一并抹掉，等它回来又落回
     * 默认态——也就是用户看到的"切走再切回就弹回"（节点页每次进入都会重新拉一次快照）。
     *
     * 断言刻意不写死"应该展开还是收起"：只要求**用户操作后的那个状态**跨瞬时缺组保持不变。
     * 这样它在旧的"默认展开 + 裁剪"语义下同样会红（旧语义里用户操作是收起、回来却变展开），
     * 而不是只针对新语义自证。
     */
    @Test
    fun `瞬时缺组不会抹掉用户的折叠态，切走再切回保持同一状态`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME))
        vm.loadProxyGroups()
        advanceUntilIdle()

        // 用户在主组卡上点了一下，得到他想要的状态
        vm.toggleSection(PRIMARY_SECTION_KEY)
        val userChoice = PRIMARY_SECTION_KEY in vm.collapsedSections.value

        // 再次进入节点页：本次快照里主组瞬时查不到（内核重载中）
        stubGroupNames(listOf(GROUP_NAME))
        vm.loadProxyGroups()
        advanceUntilIdle()

        // 下一次刷新主组回来了
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME))
        vm.loadProxyGroups()
        advanceUntilIdle()

        assertEquals(
            "用户在主组卡上的折叠选择必须跨瞬时缺组保持（问题1：切走再切回弹回）",
            userChoice,
            PRIMARY_SECTION_KEY in vm.collapsedSections.value,
        )
    }

    /**
     * 内核每次重载配置后必须自动重新拉取策略组。
     *
     * 用户实测的现场：在「分流规则管理」里改开关 → 触发内核重载（历史上还会因广播缺 uuid 把
     * TunService 整个拆掉）→ 节点页分组快照停在空列表，页面显示「27 个节点 · 0 个分组」
     * 「暂无节点，请先更新订阅」，怎么等都不回来，只有切走再切回才恢复。
     *
     * 根因是快照只在**进入节点页**时拉一次（LoggedInPages 的 LaunchedEffect(Unit)），
     * 内核重启后没有任何触发点。现在挂在 KernelManager.profileLoaded 上自愈：
     * 内核 `Clash.load` 成功 → 广播 PROFILE_LOADED → 重新拉一次。
     */
    @Test
    fun `内核重载配置后自动重新拉取分组，空快照能自愈`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()

        // 前置：进入节点页时内核不可用，快照为空——正是用户看到的「0 个分组」
        stubGroupNames(emptyList())
        vm.loadProxyGroups()
        advanceUntilIdle()
        assertTrue("前置条件：快照应为空", vm.proxyGroups.value.isEmpty())

        // 内核重启并成功载入配置：这次能查到两个组
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME))
        profileLoadedFlow.value = 1
        advanceUntilIdle()

        assertEquals(
            "内核重载后应自动重新拉取分组，不能停在空列表（用户：一暂停连接节点页东西就消失）",
            listOf(PrimaryGroupName, GROUP_NAME),
            vm.proxyGroups.value.map { it.name },
        )
    }

    @Test
    fun `收起一条分流组不影响主组卡与其余分流组`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME, "🤖 AI"))
        vm.loadProxyGroups()
        advanceUntilIdle()

        vm.toggleSection(GROUP_NAME)

        val collapsed = vm.collapsedSections.value
        assertTrue("被点收的分流组应收起", GROUP_NAME in collapsed)
        assertTrue("其余分流组不受影响（纯开关，无手风琴）", "🤖 AI" !in collapsed)
        assertTrue("主组卡不受影响", PRIMARY_SECTION_KEY !in collapsed)
    }

    /**
     * 默认展开下用户主动收起的键要**记住**（persistSections 落盘 + seen 登记不裁剪），
     * 瞬时缺组回来后仍是用户收起的样子。
     */
    @Test
    fun `用户主动收起的分流组跨组列表刷新保持收起`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME, "🤖 AI"))
        vm.loadProxyGroups()
        advanceUntilIdle()

        vm.toggleSection(GROUP_NAME)
        assertTrue("前置：用户已收起该组", GROUP_NAME in vm.collapsedSections.value)

        // 内核重载中：快照为空
        stubGroupNames(emptyList())
        vm.loadProxyGroups()
        advanceUntilIdle()

        // 分组回来
        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME, "🤖 AI"))
        vm.loadProxyGroups()
        advanceUntilIdle()

        assertTrue("用户收起的组应保持收起", GROUP_NAME in vm.collapsedSections.value)
        assertTrue("未操作的组应保持展开", "🤖 AI" !in vm.collapsedSections.value)
    }

    /**
     * P1-9 回归改写（默认展开语义）：分组快照**还没到**时不写任何折叠键；
     * 分组到达后依旧是全展开——不再有"进页全部收起"（该入口已随默认展开一起删除）。
     */
    @Test
    fun `分组未到达时不写折叠键，分组到达后保持默认展开`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        stubGroupNames(emptyList())
        vm.loadProxyGroups()
        advanceUntilIdle()

        stubGroupNames(listOf(PrimaryGroupName, GROUP_NAME))
        vm.loadProxyGroups()
        advanceUntilIdle()

        assertTrue(
            "分组到达后不得自动写任何折叠键（默认全展开）",
            vm.collapsedSections.value.isEmpty(),
        )
    }

    /** 折叠键必须与任何真实组名都不可能相等，否则两张卡会共用键互相踩。 */
    @Test
    fun `主组折叠键不与任何真实组名冲突`() {
        assertTrue("折叠键不得落在内核保留组名里", PRIMARY_SECTION_KEY !in com.slte.app.kernel.RoutingReservedNames)
        assertTrue("折叠键不得等于主组名", PRIMARY_SECTION_KEY != PrimaryGroupName)
    }

    /** 桩出单组内核（组列表可见），供回填函数直测用例装配 `_proxyGroups`。 */
    private fun stubGroupMembers(
        memberName: String,
        delay: Int,
    ) {
        kernelProxy.stubKernelBridge()
        val clash = mockk<IClashManager>(relaxed = true)
        val proxies =
            listOf(
                Proxy(name = memberName, title = memberName, subtitle = "", type = "vmess", delay = delay, isGroup = false),
            )
        every { clash.queryProxyGroupNames(any()) } returns listOf(GROUP_NAME)
        every { clash.queryProxyGroup(any(), any()) } returns
            ProxyGroup(type = "Selector", proxies = proxies, now = memberName)
        val manager = mockk<KernelManager>(relaxed = true)
        every { manager.clash() } returns clash
        every { kernelProxy.manager } returns manager
    }

    // endregion

    /**
     * 回归（真机：首页选 [直]3美国 → 日志 selectInGroup: 组成员不存在 [直]3美国）：
     * 标准订阅是「主组(niuren) → 自动选择(URLTest) 子组」的嵌套结构，真实节点挂在子组下。
     * 选真实节点必须把 patch 打到承载它的子组「自动选择」，而不是主组 niuren
     * （其成员只是子组名，不含真实节点）。同时「自动选择」是 URLTest 类自动组，
     * selectInGroup 此前只允许 Selector 会直接拒掉——这两点都修过。
     */
    @Test
    fun `嵌套组主组挂子组时选真实节点patch到承载它的子组`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns Result.success(listOf(node("香港01", 1)))
        val vm = viewModel() // 默认 connected=false，clashAlive 由 clash()!=null 决定=true
        kernelProxy.stubKernelBridge()
        val clash = mockk<IClashManager>(relaxed = true)
        // 主组 niuren：成员是子组「自动选择」（isGroup=true），不直接含真实节点
        val primaryProxies =
            listOf(
                Proxy(name = "自动选择", title = "自动选择", subtitle = "", type = "URLTest", delay = 0, isGroup = true),
            )
        // 「自动选择」子组：承载真实节点，类型 URLTest（自动测速组，可手动固定）
        val autoProxies =
            listOf(
                Proxy(name = "[直]1德国", title = "[直]1德国", subtitle = "", type = "vmess", delay = 10, isGroup = false),
                Proxy(name = "[直]3美国", title = "[直]3美国", subtitle = "", type = "vmess", delay = 20, isGroup = false),
            )
        every { clash.queryProxyGroupNames(any()) } returns listOf("niuren", "自动选择")
        every { clash.queryProxyGroup("niuren", any()) } returns
            ProxyGroup(type = "Selector", proxies = primaryProxies, now = "自动选择")
        every { clash.queryProxyGroup("自动选择", any()) } returns
            ProxyGroup(type = "URLTest", proxies = autoProxies, now = "[直]3美国")
        val manager = mockk<KernelManager>(relaxed = true)
        every { manager.clash() } returns clash
        every { kernelProxy.manager } returns manager

        vm.selectPrimary("[直]3美国")
        advanceUntilIdle()

        // 必须 patch 到承载真实节点的子组「自动选择」，而非主组 niuren
        verify { clash.patchSelector("自动选择", "[直]3美国") }
        assertNull("选择成功不应写错误消息", vm.errorMessageRes.value)
    }

    /** 以桩化的内核策略组替换默认的"未就绪"内核，供测速 / 策略组用例使用。 */
    private fun stubClashGroups(
        members: List<Pair<String, Int>>,
        now: String,
    ) {
        // 保留 safe() 直通桩，再把它挂到的 manager 换成可读策略组的内核
        kernelProxy.stubKernelBridge()
        // 测速用例走「已连接」分支（startSpeedTest 按它分流到内核测速而非 TCP 预检）
        every { kernelManager.connected } returns MutableStateFlow(true)
        val clash = mockk<IClashManager>(relaxed = true)
        val proxies =
            members.map { (name, delay) ->
                Proxy(name = name, title = name, subtitle = "", type = "vmess", delay = delay, isGroup = false)
            }
        every { clash.queryProxyGroupNames(any()) } returns listOf(GROUP_NAME)
        every { clash.queryProxyGroup(any(), any()) } returns ProxyGroup(type = "Selector", proxies = proxies, now = now)

        val manager = mockk<KernelManager>(relaxed = true)
        every { manager.clash() } returns clash
        every { kernelProxy.manager } returns manager
    }

    /**
     * 桩化多策略组内核：每个组名都返回同一个成员列表的 Selector 组。
     *
     * 可以二次调用来换一份组名列表，从而模拟"某个组从某次快照里瞬时消失"——
     * `proxyGroups()` 是逐组 `runCatching`，这是在单测里复现问题1（折叠态被裁剪）的必要手段。
     */
    private fun stubGroupNames(
        names: List<String>,
        members: List<Pair<String, Int>> = listOf("香港01" to 42),
    ) {
        kernelProxy.stubKernelBridge()
        val clash = mockk<IClashManager>(relaxed = true)
        val proxies =
            members.map { (name, delay) ->
                Proxy(name = name, title = name, subtitle = "", type = "vmess", delay = delay, isGroup = false)
            }
        every { clash.queryProxyGroupNames(any()) } returns names
        // 未命中的组名必须返回内核的哨兵：`Clash.queryGroup` 查不到组时返回
        // `ProxyGroup("Unknown", [], "")` 而不是 null（kernel-core/.../core/Clash.kt）。
        // relaxed 桩会凭空造出一个组，把「内核没有组」的现场掩盖掉。
        every { clash.queryProxyGroup(any(), any()) } returns
            ProxyGroup(type = "Unknown", proxies = emptyList(), now = "")
        names.forEach { groupName ->
            every { clash.queryProxyGroup(groupName, any()) } returns
                ProxyGroup(type = "Selector", proxies = proxies, now = proxies.firstOrNull()?.name.orEmpty())
        }

        val manager = mockk<KernelManager>(relaxed = true)
        every { manager.clash() } returns clash
        every { kernelProxy.manager } returns manager
    }

    /**
     * 回归（第 3 轮 N3）：折叠态必须跨冷启动保住。
     *
     * 真机取证链：95 图（冷启动，全部收起）→ 96 图（用户展开主组）→ **97 图（重启 App 再进
     * 节点页，又变回全部收起）**。"切走再切回"此前已修，唯独进程重启这一维度没保住。
     */
    /**
     * 冷启动（niuren 定制，默认展开语义）：**不恢复**旧收起集合——旧版本落盘的是
     * "首见即收起 + 进页全部收起"的默认态，照单全收会让升级后首启仍全收起。
     * "已见过"集合照常恢复（syncCollapsedSections 靠它区分首见）。
     */
    @Test
    fun `冷启动后默认全展开，不恢复旧版落盘的收起集合`() = runTest(mainRule.dispatcher) {
        every { subscribeRepository.getCachedUserInfo() } returns
            User(id = "1", displayName = ACCOUNT, email = ACCOUNT, authData = "auth", subscribeToken = "sub")
        // 上一次会话（旧版本）：整页收起被落盘
        nodesSectionStore.save(ACCOUNT, setOf(PRIMARY_SECTION_KEY), setOf(PRIMARY_SECTION_KEY))

        val vm = viewModel()

        assertTrue(
            "升级后冷启动应默认全展开，不照搬旧版落盘的全收起",
            vm.collapsedSections.value.isEmpty(),
        )
    }

    @Test
    fun `折叠态按账号隔离，换账号不串味`() = runTest(mainRule.dispatcher) {
        nodesSectionStore.save(ACCOUNT, setOf(PRIMARY_SECTION_KEY), setOf(PRIMARY_SECTION_KEY))
        // 当前会话没有账号信息 ⇒ 读到的是匿名那份，不该吃到别的账号的收起态
        every { subscribeRepository.getCachedUserInfo() } returns null

        val vm = viewModel()

        assertTrue("另一个账号的收起态不该生效", vm.collapsedSections.value.isEmpty())
    }

    /**
     * 离线角标必须跨"订阅刷新/冷启动"存活。
     *
     * 离线是上一次测速复核出来的结论，存在本地；如果 [applyNodes] 不读它，
     * 用户重启 App 后角标就消失，只能靠再测一次速才知道节点已经没了。
     */
    @Test
    fun `已探测离线的节点在加载时被标注`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns
            Result.success(listOf(node("香港01", 1), node("美国01", 2)))
        every { kernelProxy.speedResultStore.getOfflineNodes() } returns setOf("香港01")
        val vm = viewModel()

        vm.loadNodes(force = true)
        advanceUntilIdle()

        assertTrue(
            "缓存里标记为离线的节点应带出 offline",
            vm.data.value.nodes.first { it.name == "香港01" }.offline,
        )
        assertTrue(
            "没被标记的节点不该被判离线",
            !vm.data.value.nodes.first { it.name == "美国01" }.offline,
        )
    }

    @Test
    fun `没有离线缓存时所有节点都算在线`() = runTest(mainRule.dispatcher) {
        coEvery { serverRepository.fetchServers(any()) } returns
            Result.success(listOf(node("香港01", 1)))
        every { kernelProxy.speedResultStore.getOfflineNodes() } returns null
        val vm = viewModel()

        vm.loadNodes(force = true)
        advanceUntilIdle()

        assertTrue(
            "读不到离线名单时不能凭空标离线",
            vm.data.value.nodes.none { it.offline },
        )
    }

    // region D7：测速门标志必须由 finally 复位（异常后按钮不得永久置灰）

    /**
     * D7 回归：全部测速中途被取消后，[ServerViewModel.isTestingAll] 必须复位。
     *
     * 现场：`startSpeedTest` 把门标志置位在协程**外**（点击即进入测速态），复位却写在协程体最后
     * 一行。协程体中途跳出（取消 / 内核重载中 / binder 死亡）时那一行永远不执行，
     * 「全部测速」按钮就永久置灰，只能重启 App。
     *
     * 注入点是 `speedTestProgressive` 这个 safe 操作，注入的异常是 [CancellationException]：
     * [KernelProxy.safe] → `KernelFaultReporter.guard` 会把普通 `Exception` 吞成默认值并上报故障
     * （生产语义，见 KernelFaultReporter.kt:44-50），只有 `CancellationException` 被原样重抛——
     * 也就是说真实世界里能让门标志卡死的正是取消（页面销毁 / viewModelScope 取消 / 内核调用被取消），
     * 注入普通异常只会测到测试桩自己，证明不了生产语义。
     */
    @Test
    fun `全部测速被取消后测速门必须复位`() = runTest(mainRule.dispatcher) {
        val vmRef = arrayOfNulls<ServerViewModel>(1)
        var gateWhenKernelCalled = false
        val vm = viewModelWithKernelCancelled("speedTestProgressive") {
            gateWhenKernelCalled = vmRef[0]!!.isTestingAll.value
        }
        vmRef[0] = vm

        vm.startSpeedTest()
        assertTrue("前置条件：点击后应立刻进入测速态", vm.isTestingAll.value)
        advanceUntilIdle()

        // 前置条件在内核调用发生的那一刻取样：证明门标志确实被置起过，否则「最后是 false」
        // 可能只是因为压根没进入测速态，测试就自证了。
        assertTrue("前置条件：内核调用发生的那一刻测速门应处于置位状态", gateWhenKernelCalled)
        assertTrue(
            "测速被取消后必须复位门标志，否则按钮永久置灰（D7）",
            !vm.isTestingAll.value,
        )
    }

    /**
     * D7 回归（单组测速）：[ServerViewModel.testGroup] 的 `_testingGroup` 同样没有 finally。
     *
     * 该标志是「某一行正在转圈」的唯一依据：中途跳出后残留，用户再点任何一组的测速都会被顶部的
     * `if (_testingGroup.value != null) return` 吃掉，而转圈动画还挂在旧那一行上。
     */
    @Test
    fun `单组测速被取消后测速门必须复位`() = runTest(mainRule.dispatcher) {
        val vmRef = arrayOfNulls<ServerViewModel>(1)
        var gateWhenKernelCalled: String? = null
        val vm = viewModelWithKernelCancelled("testGroup") {
            gateWhenKernelCalled = vmRef[0]!!.testingGroup.value
        }
        vmRef[0] = vm

        vm.testGroup(GROUP_NAME)
        advanceUntilIdle()

        assertEquals("前置条件：内核调用发生时该组应处于测速态", GROUP_NAME, gateWhenKernelCalled)
        assertNull(
            "单组测速被取消后必须复位门标志，否则整页测速按钮全部失效（D7）",
            vm.testingGroup.value,
        )
    }

    /**
     * 构造一个「指定 safe 操作被取消」的 ViewModel：`safe` 是本用例里**唯一一次**被桩的方法。
     *
     * 不能先 [stubKernelBridge] 再把 `safe` 覆盖掉：`safe` 是 suspend 方法，MockK 对已经注册过
     * `coAnswers` 的同签名方法再录一次桩时会先把新答案求值，异常直接从打桩那一行冒出来
     * （实测栈顶落在打桩行 `viewModelWithKernelThrowing(...)`，压根走不到被测代码）。
     * 所以这里手工做 [stubKernelBridge] 除 `safe` 以外的等价桩，让 `safe` 只被注册一次。
     *
     * 答案里按操作名分流：只有 [cancelledOperation] 被取消，其余操作照常执行真实块、普通异常吞成
     * 默认值——init 里的 `refreshSpecialNodes` / `observeKernelReloads` 等后台协程因此不受影响。
     *
     * @param cancelledOperation 要注入取消的 safe 操作名（见 KernelProxyGroup.kt / KernelProxySpeed.kt）
     * @param onKernelCall 该操作真的被调用时回调一次，用于在那一刻取样门标志
     */
    private fun viewModelWithKernelCancelled(
        cancelledOperation: String,
        onKernelCall: () -> Unit = {},
    ): ServerViewModel {
        coEvery { kernelProxy.safe<Any?>(any(), any(), any(), any()) } coAnswers {
            if (arg<String>(1) == cancelledOperation) {
                onKernelCall()
                throw CancellationException("kernel call cancelled")
            }
            try {
                arg<suspend () -> Any?>(3).invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firstArg<Any?>()
            }
        }
        every { kernelProxy.manager } returns mockk(relaxed = true)
        every { subscribeRepository.getCachedSubscribeInfo() } returns null
        every { kernelManager.profileLoaded } returns profileLoadedFlow
        // connected 给真流且置 true：startSpeedTest 按 connected 分流，false 会走 TCP 预检分支
        // （不经过 safe("speedTestProgressive")，取消注入根本不会发生）
        every { kernelManager.connected } returns MutableStateFlow(true)
        return ServerViewModel(
            serverRepository,
            subscribeRepository,
            kernelProxy,
            kernelManager,
            nodesSectionStore,
            SessionStore(InMemoryPreferences()),
        )
    }

    // endregion

    // region 内核延迟 key → 订阅名映射（"点测速跑完了但整页停在未测"回归）

    /**
     * 内核测速结果的 key 是内核节点名（标准订阅带国旗前缀），订阅列表是原始名。
     * 旧实现直接 `delays[node.name]` 精确取值永远取不到——真机表现就是
     * "首页面板点测速跑完一整轮，所有节点仍显示「未测」"。
     */
    @Test
    fun `内核延迟key映射回订阅名`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        val mapped =
            vm.remapDelayKeysToPanelNames(
                mapOf("🇩🇪 [直]1德国" to 210, "🇺🇸 [直]3美国" to 201),
                listOf("[直]1德国", "[直]2德国", "[直]3美国"),
            )
        assertEquals(210, mapped["[直]1德国"])
        assertEquals(201, mapped["[直]3美国"])
        assertEquals(2, mapped.size)
    }

    /** `[直]` 与 `[CF]` 只差前导标签：映射必须保留标签，不得互相折叠。 */
    @Test
    fun `映射保留直与CF标签不互相折叠`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        val mapped =
            vm.remapDelayKeysToPanelNames(
                mapOf("🇩🇪 [CF]1德国" to 999),
                listOf("[直]1德国", "[CF]1德国"),
            )
        assertEquals(999, mapped["[CF]1德国"])
        assertEquals(1, mapped.size)
        assertTrue(!mapped.containsKey("[直]1德国"))
    }

    /** 映射不上的 key 保留原样（调用方按订阅名取不到就当没测过，不丢数据）。 */
    @Test
    fun `映射不上的key保留原样`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        val mapped =
            vm.remapDelayKeysToPanelNames(
                mapOf("未知节点" to 88),
                listOf("[直]1德国"),
            )
        assertEquals(88, mapped["未知节点"])
    }

    /** 空表 / 空订阅名直接原样返回，不做多余拷贝。 */
    @Test
    fun `空输入原样返回`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        val delays = mapOf("a" to 1)
        assertEquals(delays, vm.remapDelayKeysToPanelNames(delays, emptyList()))
        assertTrue(vm.remapDelayKeysToPanelNames(emptyMap(), listOf("a")).isEmpty())
    }

    // endregion

    private companion object {

        const val GROUP_NAME = "节点选择"

        const val ACCOUNT = "a@example.com"
    }
}
