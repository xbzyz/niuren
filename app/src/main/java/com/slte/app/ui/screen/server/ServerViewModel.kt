// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slte.app.R
import com.slte.app.data.local.NodesSectionStore
import com.slte.app.data.repository.ServerRepository
import com.slte.app.data.repository.SubscribeRepository
import com.slte.app.kernel.KernelManager
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.KernelProxyGroupInfo
import com.slte.app.kernel.KernelUrlTestFailure
import com.slte.app.kernel.NodeNameResolver
import com.slte.app.kernel.SpeedTestOutcome
import com.slte.app.kernel.cachedOfflineNodes
import com.slte.app.kernel.cachedSpeedResults
import com.slte.app.kernel.clashAlive
import com.slte.app.kernel.groupByTypeCurrentNode
import com.slte.app.kernel.primaryGroupOf
import com.slte.app.kernel.proxyGroups
import com.slte.app.kernel.saveOfflineNodes
import com.slte.app.kernel.selectAuto
import com.slte.app.kernel.selectFallback
import com.slte.app.kernel.selectInGroup
import com.slte.app.kernel.selectPrimary
import com.slte.app.kernel.speedTestOutcome
import com.slte.app.kernel.speedTestProgressiveAndCache
import com.slte.app.kernel.testGroup
import com.slte.app.kernel.urlTestFailureKind
import com.slte.app.utils.Constants
import com.slte.app.utils.ErrorMessages
import com.slte.app.utils.extractCountryCode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ServerViewModel
@Inject
constructor(
    private val serverRepository: ServerRepository,
    private val subscribeRepository: SubscribeRepository,
    private val kernelProxy: KernelProxy,
    private val kernelManager: KernelManager,
    private val nodesSectionStore: NodesSectionStore,
    private val sessionStore: com.slte.app.data.local.SessionStore,
) : ViewModel() {

    /**
     * TCP 握手测速的 IO 调度器（internal var 仅供单测注入受控调度器——真实 IO 线程
     * 不进测试调度器，advanceUntilIdle 等不到握手结果，断言会拿到旧值）。
     */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private fun hasPlan(): Boolean = subscribeRepository.getCachedSubscribeInfo()?.hasPlan ?: true

    private val _data = MutableStateFlow(ServerData())
    val data: StateFlow<ServerData> = _data.asStateFlow()

    private val _errorMessageRes = MutableStateFlow<Int?>(null)
    val errorMessageRes: StateFlow<Int?> = _errorMessageRes.asStateFlow()

    private val _proxyGroups = MutableStateFlow<List<KernelProxyGroupInfo>>(emptyList())
    val proxyGroups: StateFlow<List<KernelProxyGroupInfo>> = _proxyGroups.asStateFlow()

    private val _isLoadingGroups = MutableStateFlow(false)
    val isLoadingGroups: StateFlow<Boolean> = _isLoadingGroups.asStateFlow()

    private val _customGroupNames = MutableStateFlow<Set<String>?>(null)

    /**
     * 用户自建分流组名，**只供渲染层决定显示名**（见 groupDisplayName）。
     *
     * 显示映射必须放过自建组：用户完全可以把自建组叫成某个旧内置组名——v1.11.6 时
     * 「Ⓜ️ 微软」还是内置组名（`RoutingInputValidator.reservedNames` 含
     * `RoutingGroups.map { it.name }`），用户当时**建不了**同名自建组；1.12.0 改名
     * （提交 `1563d093`）后它才不再是保留名，所以今天完全可能存在一个叫「Ⓜ️ 微软」的
     * 用户自建组。那是他自己的名字，不能因为内核还挂着旧名就被替他改名。
     *
     * **读盘时机（线程模型 + 量级证据）**：初始化时读一次并缓存，刷新时只用缓存、**不重复读盘**；
     * 内核每次重载配置成功（[KernelManager.profileLoaded]，改分流规则会触发重载）时刷新缓存
     * ——缓存有明确失效点，所以不会在用户增删自建组后过期误映射。
     *
     * 之所以不做「每次刷新都读」：routing.json 实测规模 < 1 KB（含 2 条自定义组 + 3 条直连域名的
     * 代表性文件 675 B），容器上纯文件读 20,000 次平均 **6.0 µs**（手机内存储更慢，但同为亚毫秒级）；
     * 而 [refreshGroups] 每次进节点页 / 选组成功 / 测速后都会调用。量级上虽可接受，但没有必要把
     * 文件 IO 放进刷新路径——缓存 + 重载失效同时满足「不重复读盘」和「不过期」。
     *
     * 读盘用 [com.slte.app.kernel.RoutingStateStore.readOrNull] 而不是 loadSanitized：后者会做
     * 清洗回写，节点页没有理由触发那次写盘（清洗语义是另一条线）。
     *
     * **null = 读不出来**（文件在但解析失败）→ 渲染层保守不映射；空集 = 确实没有自建组 → 照常映射。
     * 初值也是 null：初始化那次读落地前宁可保守显示内核原名，也不要误改用户自建组。
     */
    val customGroupNames: StateFlow<Set<String>?> = _customGroupNames.asStateFlow()

    private val _testingGroup = MutableStateFlow<String?>(null)
    val testingGroup: StateFlow<String?> = _testingGroup.asStateFlow()

    private val _isTestingAll = MutableStateFlow(false)

    /** 顶部「测速」触发的全组测速是否进行中（页面只渲染策略组，单独暴露一个布尔）。 */
    val isTestingAll: StateFlow<Boolean> = _isTestingAll.asStateFlow()

    private val _speedTestTipRes = MutableStateFlow<Int?>(null)

    /** 节点页「进入即自动测速」是否已跑过：VM 存活期 = 一个 App 会话，故只自动跑一次。 */
    private var autoSpeedTestOnEnter = false

    /** 全组测速结束提示（完成 / 失败），UI 取用后需 consume。 */
    val speedTestTipRes: StateFlow<Int?> = _speedTestTipRes.asStateFlow()

    /**
     * 未连接时预选的成员名（niuren 定制，与桌面端同口径）。
     *
     * 节点页离线名单的高亮来源：点了但内核进程没起时，勾选先落在pending 上，
     * 连接成功（由 MainViewModel 重放并清掉存储）或重新连上内核后归还给
     * 内核回读的 now。连接态下恒为 null——高亮以内核回读为准。
     */
    private val _pendingNode = MutableStateFlow<String?>(sessionStore.getPendingNode())
    val pendingNode: StateFlow<String?> = _pendingNode.asStateFlow()

    /**
     * 连接态下手动选择**成功落地内核**的自增信号（niuren 定制）。
     *
     * 首页出口节点名来自 MainViewModel 的 DashboardData（`info.node`），只有
     * `refreshKernelInfo()` 才会更新——切节点成功后若不主动刷，首页节点行和
     * 选择面板的勾选要等下次连接才动，看起来就是"选了不生效"（真机反馈）。
     * 首页订阅这个 tick，跳动即延时调 `refreshKernelInfo()` 回读真实出口。
     */
    private val _selectionAppliedTick = MutableStateFlow(0)
    val selectionAppliedTick: StateFlow<Int> = _selectionAppliedTick.asStateFlow()

    /**
     * 节点页已收起的区块（**按组名**，集合内 = 收起）。
     *
     * 两点是刻意的：
     * 1. 放在 ViewModel 而不是屏幕内 `remember`：Tab 内容是
     *    `AnimatedContent(contentKey = { tab to leaf })`，切 Tab 会销毁整棵子树，
     *    局部状态（含 `rememberSaveable`，本仓没有 SaveableStateHolder）必丢，
     *    表现为"收起后切走再回来又全展开"。
     * 2. 用组名做 key 而不是组对象/下标：每次测速或订阅更新都会整体重建
     *    `KernelProxyGroupInfo` 列表，而同名对象的 now/members 已变化故不相等，
     *    用对象做 key 会导致折叠态在刷新后静默失效。
     *
     * 3. **跨冷启动持久化**（第 3 轮 N3）：存进 [NodesSectionStore]，按账号隔离。
     *    此前只在内存里、重启复位为默认折叠，用户"展开过"的分组下次启动又全收起
     *    （真机取证：95 冷启动全收起 → 96 用户展开 → 97 重启后又全收起）。
     *
     * 集合内 = 已收起。**默认全部展开**（niuren 定制）：集合只在用户**主动收起**某个区块时
     * 才写入键——这样"默认展开"不需要在 UI 里散落 if-else，且用户手动收起的区块
     * 冷启动后仍保持收起（是真偏好，不是默认态）。
     */
    private val _collapsedSections = MutableStateFlow<Set<String>>(emptySet())
    val collapsedSections: StateFlow<Set<String>> = _collapsedSections.asStateFlow()

    /**
     * 已出现过的区块键（只增不减）。
     *
     * "只增不减"是刻意的：折叠集合是**用户偏好记忆**，而 [KernelProxy.proxyGroups] 是逐组
     * `runCatching`，内核重载 / 瞬时查询失败都会让某个组从某次快照里消失。若按"当前快照里
     * 有哪些组"去裁剪折叠集合，这个组消失一次就会把用户刚收起的键抹掉，等它回来又变成
     * 默认态——现象正是"节点选择的折叠态留不住、切走再切回就弹回"。
     * 容器大小只受"见过的组名个数"约束，不构成泄漏。
     */
    private val seenSections = mutableSetOf<String>()

    /** 折叠态按账号隔离（同一台设备换账号登录时不互相污染）。必须声明在 init 之前。 */
    private val account: String = subscribeRepository.getCachedUserInfo()?.email.orEmpty()

    init {

        // 折叠态跨冷启动持久化：只读回"已见过"集合（syncCollapsedSections 仍靠它区分首见）。
        // 收起集合**不再恢复**：旧版本存的是"默认全收起"（首见即收起 + 进页全部收起都会落盘），
        // 若照单全收，升级后首启节点页还是全收起——正是本轮要改掉的默认行为。改为默认全展开，
        // 之后用户手动收起的键会重新积累（persistSections 照常落盘）。
        seenSections += nodesSectionStore.seenFor(account)

        val cachedDelays = kernelProxy.cachedSpeedResults()
        serverRepository.getCachedServers()?.let { applyNodes(it, cachedDelays) }
        refreshSpecialNodes()
        // 自建组名：初始化读一次并缓存（之后只在 profileLoaded 时刷新，见 [customGroupNames]）
        _customGroupNames.value = customGroupNamesFromStore()
        // 未连接预选（niuren 定制）：断开时跟随持久化存储（覆盖冷启动恢复场景），
        // 连接后清空——高亮交给内核回读的 now
        viewModelScope.launch {
            kernelManager.connected.collect { connected ->
                _pendingNode.value = if (connected) null else sessionStore.getPendingNode()
            }
        }
        observeKernelReloads()
    }

    /**
     * VPN 未 RUNNING 但内核服务活着时保险记忆一份选择：连接会重载 profile，
     * 可能重置选择器，连接就绪后由 MainViewModel 重放（幂等）。
     */
    private fun rememberPendingWhenDisconnected(name: String) {
        if (!kernelManager.connected.value) {
            sessionStore.savePendingNode(name)
            _pendingNode.value = name
        }
    }

    private fun persistSections() {
        nodesSectionStore.save(account, _collapsedSections.value, seenSections)
    }

    /**
     * 内核每次(重)载配置成功后重新拉取策略组。
     *
     * 此前分组快照只在**进入节点页**时拉一次（LoggedInPages 的 `LaunchedEffect(Unit)`）。
     * 于是只要内核在用户停留在节点页期间重启（改分流规则触发重载、切模式、服务被系统重建……），
     * `_proxyGroups` 就会永久停在空列表：页面显示「27 个节点 · 0 个分组」「暂无节点，请先更新订阅」，
     * 怎么等都不回来，只有切走再切回（重建组合→重新拉一次）才恢复——用户实测到的
     * "一暂停连接，节点页里的东西就消失"正是这个。
     *
     * 挂钩 [KernelManager.profileLoaded]（内核 `Clash.load` 成功后才发出）而不是连接状态：
     * 它保证"内核确实已有一份可查询的新配置"，此刻拉取才有意义。
     */
    private fun observeKernelReloads() {
        viewModelScope.launch {
            kernelManager.profileLoaded.collect {
                // 分流规则变化会走重载：此刻刷新自建组名缓存（用户可能刚增删了自定义组）
                _customGroupNames.value = customGroupNamesFromStore()
                val groups = kernelProxy.proxyGroups()
                // 内核刚起来的瞬间可能查不到组，此时不覆盖已有列表——保留旧数据等下一次，
                // 免得把本来正常的页面刷成空。列表本来就是空时照常写入，否则永远自愈不了。
                if (groups.isNotEmpty() || _proxyGroups.value.isEmpty()) {
                    refreshPrimarySectionState(groups)
                }
                // 内核重载意味着订阅/配置刚变：节点集合也可能变了，用仓库缓存重读一次，
                // 否则"更新订阅"后节点页列表仍是旧的（切走再切回才更新）。
                refreshNodesFromCache()
            }
        }
    }

    private fun refreshSpecialNodes() {
        viewModelScope.launch {
            val auto = kernelProxy.groupByTypeCurrentNode("URLTest")
            val fallback = kernelProxy.groupByTypeCurrentNode("Fallback")
            _data.update { state ->
                state.copy(
                    autoNode = auto,
                    fallbackNode = fallback,
                    autoNodeCountryCode = countryOf(auto),
                    fallbackNodeCountryCode = countryOf(fallback),
                )
            }
        }
    }

    private fun countryOf(nodeName: String?): String? = nodeName?.let { name ->
        _data.value.nodes
            .firstOrNull { it.name == name }
            ?.countryCode
            ?.takeIf { it != "XX" }
    }

    suspend fun refreshNodesForPurchase() {
        serverRepository.fetchServers(force = true).fold(
            onSuccess = { applyNodes(it) },
            onFailure = { _errorMessageRes.value = ErrorMessages.forServer(it) },
        )
    }

    fun retry() {
        loadNodes()
    }

    fun dismissError() {
        _errorMessageRes.value = null
    }

    /**
     * 用仓库缓存重新填充节点列表（**不发网络请求**）。
     *
     * 订阅更新流程（`SubscriptionUpdater`）只刷新 `ServerRepository` 的缓存并更新首页
     * DashboardData，从不通知本 VM——于是节点页点「更新订阅」转完圈后列表纹丝不动，
     * 必须切走再切回才更新。这里给外部一条"缓存已变，请重读"的入口。
     */
    fun refreshNodesFromCache() {
        // 缓存读不出来/为空时**绝不能**用空列表覆盖：那会把本来正常的列表刷成空
        // （与 observeKernelReloads 里"组列表为空不覆盖"同一口径）
        val cached = serverRepository.getCachedServers()?.takeIf { it.isNotEmpty() } ?: return
        applyNodes(cached)
    }

    fun loadNodes(force: Boolean = false) {
        _errorMessageRes.value = null
        _data.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            serverRepository.fetchServers(force = force).fold(
                onSuccess = ::applyNodes,
                onFailure = { throwable ->
                    _data.update { it.copy(isLoading = false) }
                    _errorMessageRes.value = ErrorMessages.forServer(throwable)
                },
            )
        }
    }

    private fun applyNodes(
        servers: List<com.slte.app.domain.model.ServerNode>,
        delays: Map<String, Int>? = null,
    ) {
        val existing = _data.value.nodes.associate { it.name to it.delay }
        // 缓存/内核测速结果的 key 是内核侧节点名（带国旗前缀：🇩🇪 [直]1德国），
        // 订阅列表是原始名（[直]1德国），必须先映射回订阅名再按名取值
        // （否则表现为"点测速跑完了，节点行全部停在「未测」"）。
        val mappedDelays =
            delays?.let {
                remapDelayKeysToPanelNames(it, servers.map { server -> server.name })
            }
        // 上次探测确认离线的节点：订阅刷新后角标要跟着回来，否则用户重启 App
        // 就看不到"这节点已经没了"，只能靠重新测速才知道。
        val offline = kernelProxy.cachedOfflineNodes().orEmpty()
        val nodes =
            servers
                .distinctBy { it.name }
                .mapIndexed { index, server ->
                    NodeItem(
                        id = index + 1,
                        name = server.name,
                        countryCode = extractCountryCode(server.name),
                        type = server.type.name,
                        host = server.host,
                        port = server.port,
                        delay = mappedDelays?.get(server.name) ?: existing[server.name],
                        offline = server.name in offline,
                    )
                }
        _data.update { it.copy(nodes = nodes, isLoading = false) }
        refreshSpecialNodes()
    }

    fun selectNode(nodeId: Int) {
        when (nodeId) {
            0 -> {
                _data.update { it.copy(selectedNodeId = 0) }
                viewModelScope.launch {
                    selectSpecialMember(com.slte.app.kernel.AUTO_GROUP_NAME) { kernelProxy.selectAuto() }
                }
            }
            -1 -> {
                _data.update { it.copy(selectedNodeId = -1) }
                viewModelScope.launch {
                    selectSpecialMember(com.slte.app.kernel.FALLBACK_GROUP_NAME) {
                        kernelProxy.selectFallback()
                    }
                }
            }
            else -> {
                val node = _data.value.nodes.firstOrNull { it.id == nodeId } ?: return
                // selectedNodeId 是 v5 之前的遗留字段（v5 节点页的选中态完全由
                // 内核回读的主组 now 决定），保留写入只为兼容既有调用方/用例。
                _data.update { it.copy(selectedNodeId = nodeId) }
                viewModelScope.launch {
                    selectPrimaryMember(node.name)
                    // 主选择组当前项变化后同步策略组快照，否则节点页高亮与
                    // 各分流组的「当前出口」显示会停留在旧值
                    refreshGroupsIfLoaded()
                }
            }
        }
    }

    /**
     * 节点页「节点选择」卡的主组切换（成员可以是组：自动选择 / 故障转移）。
     * 失败必须有可见反馈——旧路径丢弃了内核返回的 Boolean，用户点了没反应。
     *
     * **组名不硬编码**：`primaryGroupOf` 在主组缺失（本地分流关闭 / 面板改名）时会
     * 回退到第一个 Selectable 组，若这里写死 `PrimaryGroupName` 就会 patch 到用户
     * 没在看的那一组，表现为"点了没用"。成功判据由 [selectInGroup] 的回读确认给出。
     */
    suspend fun selectPrimaryMember(memberName: String) {
        // 内核服务活着就**立刻 patch**（不要求 VPN 已 RUNNING）：服务进程在，
        // AIDL 就能通，选择即时生效。以前按 kernelManager.connected 判——VPN 一断
        // 就退到"记住"分支，但节点页的分组/延迟照常显示（内核自带拨测），
        // 用户看到的就是"列表点得动、勾选永远不动"的假死（真机反馈）。
        // 只有内核进程都没起（clash AIDL 够不着）才把选择**记住**，连接成功后
        // 自动应用（MainViewModel 的连接就绪回调），与桌面端同口径。
        if (!kernelProxy.clashAlive()) {
            sessionStore.savePendingNode(memberName)
            _pendingNode.value = memberName
            _errorMessageRes.value = null
            _speedTestTipRes.value = R.string.node_selection_pending
            return
        }
        // 首页进页时才会拉组列表；用户抢在组列表就绪前点选、或进页拉取尚未返回时，
        // 这里按需补拉一次取真实主组名（"niuren"），避免回退字面量 PrimaryGroupName
        // （"🚀 节点选择"）把选择 patch 到不存在的组——表现为"选不了节点"（真实反馈根因）。
        // 主组（如 niuren）的成员是「自动选择」子组，真实节点挂在子组下；
        // 直接 patch 主组会因"组成员不存在"失败。这里解析出承载该真实节点的组。
        val targetName =
            com.slte.app.kernel.resolveCarryingGroup(_proxyGroups.value, memberName)
                ?: run {
                    refreshGroups()
                    com.slte.app.kernel.resolveCarryingGroup(_proxyGroups.value, memberName)
                } ?: com.slte.app.kernel.PrimaryGroupName
        if (!kernelProxy.selectPrimary(targetName, memberName)) {
            // patch 没成功分两种情况（真机反馈：未连接选节点弹「切换失败」）：
            // - VPN 未连接：内核里 profile 可能还没加载、成员名单也可能与面板订阅
            //   不同步，patch 不动是常态。用户此刻只是想"先选好节点再连接"——
            //   降级为记住选择（连接就绪后由 MainViewModel 自动应用），别让
            //   「切换失败」把入口堵死。
            // - VPN 连接中：profile 肯定已加载，patch 失败才是真故障，必须提示。
            if (!kernelManager.connected.value) {
                sessionStore.savePendingNode(memberName)
                _pendingNode.value = memberName
                _errorMessageRes.value = null
                _speedTestTipRes.value = R.string.node_selection_pending
                return
            }
            _errorMessageRes.value = R.string.proxy_group_select_failed
            return
        }
        // VPN 未 RUNNING：连接时的 profile 重载可能重置选择器，保险记一份，
        // 连接就绪后重放（幂等，见 rememberPendingWhenDisconnected）。
        // 连接中也持久化：pending = 用户最近一次手动选择，下次连接就绪由
        // MainViewModel 重放恢复，且自动测速不再 selectAuto 拉回最快节点
        //（真机反馈"选 3 变 1"）。
        sessionStore.savePendingNode(memberName)
        rememberPendingWhenDisconnected(memberName)
        refreshSpecialNodes()
        refreshGroupsIfLoaded()
        // 连接态下内核已确认切到位：发信号让首页立刻回读出口（见 _selectionAppliedTick）
        if (kernelManager.connected.value) _selectionAppliedTick.value += 1
    }

    /**
     * 「自动选择 / 故障转移」这类特殊成员的切换。
     *
     * 失败必须有可见反馈：旧实现直接丢弃了内核返回的 Boolean，点「自动选择」若
     * patch 没生效，勾选不动、也不报错，只能重启 App——与 [selectPrimaryMember]
     * 的语义保持一致（未连接降级为记住选择，连接中失败才是真故障）。
     */
    private suspend fun selectSpecialMember(
        name: String,
        apply: suspend () -> Boolean,
    ) {
        if (!kernelProxy.clashAlive()) {
            // 内核进程没起：记住选择，连接后自动应用
            sessionStore.savePendingNode(name)
            _pendingNode.value = name
            _speedTestTipRes.value = R.string.node_selection_pending
            return
        }
        if (!apply()) {
            if (!kernelManager.connected.value) {
                sessionStore.savePendingNode(name)
                _pendingNode.value = name
                _speedTestTipRes.value = R.string.node_selection_pending
                return
            }
            _errorMessageRes.value = R.string.proxy_group_select_failed
            return
        }
        // pending = 用户最近一次手动选择，连接中也持久化，下次连接重放恢复
        sessionStore.savePendingNode(name)
        rememberPendingWhenDisconnected(name)
        refreshSpecialNodes()
        refreshGroupsIfLoaded()
        if (kernelManager.connected.value) _selectionAppliedTick.value += 1
    }

    /** 保留给旧调用点的入口（后台线程外的 UI 层统一走 [selectPrimaryMember]）。 */
    fun selectPrimary(name: String) {
        viewModelScope.launch { selectPrimaryMember(name) }
    }

    /** 仅在策略组已加载过时刷新，避免首次进入节点页前发起多余的组查询。 */
    private suspend fun refreshGroupsIfLoaded() {
        if (_proxyGroups.value.isEmpty()) return
        refreshPrimarySectionState(kernelProxy.proxyGroups())
    }

    fun startSpeedTest() {
        if (_isTestingAll.value) return
        if (!hasPlan()) {
            _errorMessageRes.value = R.string.dashboard_no_plan_tip
            return
        }
        _errorMessageRes.value = null
        // 先置位再启协程：点击即进入测速态，重复点击直接被上面的守卫吃掉。
        // 门标志的复位必须走 finally：内核重载中 / binder 死亡 / 远程调用抛异常时，
        // 协程体会直接跳出，写在末尾的复位永远不执行，按钮就此永久置灰（只能重启 App）。
        _isTestingAll.value = true
        viewModelScope.launch {
            try {
                // 分流按 clashAlive（内核服务进程可通信）而不是 VPN 是否 RUNNING：
                // 内核 healthCheck 由服务进程直拨节点（不经过 TUN），VPN 断开也能测出
                // **经代理协议的真延迟**——真机反馈"未连接也可以测试真延迟"就是它。
                // 只有内核进程都没起（clash AIDL 够不着）才退回 TCP 握手测速。
                if (kernelProxy.clashAlive()) {
                    runKernelSpeedTest()
                } else {
                    runTcpPreviewSpeedTest()
                }
            } finally {
                _isTestingAll.value = false
            }
        }
    }

    /**
     * 内核完整测速（真延迟）：经代理协议拨测，VPN 未 RUNNING 时同样可用。
     * 本地分流下结构组已 include-all，测速只 await 结构组（见 KernelProxySpeed）。
     */
    private suspend fun runKernelSpeedTest() {
        val panelNames = _data.value.nodes.map { it.name }
        // force = true：用户手动点测速必须真跑一轮，不能被 15s 去重窗口用缓存结果打发
        val delays =
            kernelProxy.speedTestProgressiveAndCache(force = true) { partial ->
                // 渐进回填：内核 key（带国旗前缀）映射回订阅名后立即刷列表，
                // 用户能看到延迟逐个跳出，而不是干等一整轮结束
                val mapped = remapDelayKeysToPanelNames(partial, panelNames)
                if (mapped.isNotEmpty()) {
                    _data.update { state -> state.copy(nodes = mergeDelays(state.nodes, mapped)) }
                }
            }
        // 测速结果的 key 是内核侧节点名（🇩🇪 [直]1德国），订阅列表是 [直]1德国——
        // 直接按名取值永远取不到，表现为"点测速一秒结束但整页停在「未测」"
        // （真机反馈"这里测试不出来延迟"的根因，与选节点"组成员不存在"同源）。
        val panelDelays = remapDelayKeysToPanelNames(delays, panelNames)
        refreshGroups()
        // 测速失败的节点再逐个复核一次：只有内核明确说"不可达"才标离线，
        // 单纯超时可能只是一次抖动，标离线会误伤活节点。
        // delays 为空 = 内核根本没跑起来，此时不做复核（否则整页节点都会被当成失败）。
        val offline = if (panelDelays.isEmpty()) null else probeFailedNodes(panelDelays)
        offline?.let { kernelProxy.saveOfflineNodes(it) }
        // 同一份 delays 回填节点列表：此前只刷新策略组，导致「全部节点」
        // 列表停留在旧值、与分组卡片显示两个不同数字
        _data.update { state ->
            state.copy(
                nodes =
                mergeDelays(state.nodes, panelDelays).map { node ->
                    if (offline == null) node else node.copy(offline = node.name in offline)
                },
            )
        }
        _speedTestTipRes.value =
            when (speedTestOutcome(panelDelays, completed = true)) {
                SpeedTestOutcome.COMPLETE -> R.string.server_speed_test_done
                SpeedTestOutcome.PARTIAL -> R.string.server_speed_test_done
                SpeedTestOutcome.EMPTY -> R.string.server_speed_test_failed
            }
    }

    /**
     * TCP 握手测速（内核进程没起时的兜底）：直连 server:port 计时，与桌面端
     * previewSpeedTest 同口径。数字是"到服务器的直连握手往返"，比经代理测的偏小，
     * 但足够比较节点快慢、挑出不通的。
     */
    private suspend fun runTcpPreviewSpeedTest() {
        val delays = previewTcpSpeedTest()
        // 只缓存真实握手成功的节点：超时 999 落库没有意义。
        // 落盘 key 必须是**内核名**：speed_results 这一个键空间同时被内核测速路径
        // 写入内核名（带国旗前缀），这里是订阅名，且 SessionStore 是整表替换——
        // 直接落盘会把内核缓存全部抹掉（连接后首页「· xx ms」与分组延迟退回未测）。
        // 映射不到内核名（组列表还没拉到）时宁可不落盘，也不能污染键空间。
        val okDelays = delays.filterValues { it < Constants.DELAY_TIMEOUT }
        val storeable = remapDelayKeysToKernelNames(okDelays)
        if (storeable.isNotEmpty()) sessionStore.saveSpeedResults(storeable)
        _data.update { state -> state.copy(nodes = mergeDelays(state.nodes, delays)) }
        // 组列表若还在（上一轮连接残留的快照），就地回填组内成员——
        // 与 testGroup 同口径，否则残留组行永远看不到结果。
        mergeDelaysIntoGroups(delays)
        _speedTestTipRes.value =
            when (speedTestOutcome(delays, completed = true)) {
                SpeedTestOutcome.EMPTY -> R.string.server_speed_test_failed
                else -> R.string.server_speed_test_done
            }
    }

    /**
     * 未连接版的测速：对每个节点的 server:port 做 TCP 握手计时（并发全开，5s 超时）。
     * 握手成功的节点给出毫秒数；失败的节点显式记 [Constants.DELAY_TIMEOUT]（超时）——
     * 否则行上停在「未测」，用户无法区分「没测到」和「测了但不通」。
     */
    private suspend fun previewTcpSpeedTest(): Map<String, Int> =
        coroutineScope {
            val nodes = _data.value.nodes
            if (nodes.isEmpty()) {
                emptyMap()
            } else {
                val mutexResults = java.util.concurrent.ConcurrentHashMap<String, Int>()
                nodes
                    .map { node ->
                        async {
                            val ms = tcpPingMs(node.host, node.port)
                            mutexResults[node.name] = if (ms > 0) ms else Constants.DELAY_TIMEOUT
                        }
                    }.awaitAll()
                mutexResults.toMap()
            }
        }

    private suspend fun tcpPingMs(
        host: String,
        port: Int,
        timeoutMs: Int = 5000,
    ): Int =
        kotlinx.coroutines.withContext(ioDispatcher) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val socket = java.net.Socket()
            try {
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                (android.os.SystemClock.elapsedRealtime() - t0).toInt()
            } catch (_: Exception) {
                -1
            } finally {
                runCatching { socket.close() }
            }
        }

    /**
     * 对测速失败的节点逐个复核，返回内核明确判定"不可达"的节点名。
     *
     * 只在测速跑出结果后调用（见 [startSpeedTest]）：内核没起来时 delays 为空，
     * 那时把整页节点当失败去复核，会把一次环境故障放大成"所有节点都离线"。
     *
     * 复核并发跑，总耗时约等于单个超时（[Constants.NODE_URLTEST_TIMEOUT_MS]）。
     */
    private suspend fun probeFailedNodes(delays: Map<String, Int>): Set<String> {
        if (delays.isEmpty()) return emptySet()
        val failed =
            _data.value.nodes
                .map { it.name }
                .filter { delays[it] == null || delays[it] == Constants.DELAY_TIMEOUT }
        if (failed.isEmpty()) return emptySet()
        // 复核走内核 AIDL urlTest，必须给内核侧节点名（带国旗前缀），订阅名查不到；
        // 组成员在 runKernelSpeedTest 的 refreshGroups() 之后是最新的。
        val kernelNames = _proxyGroups.value.flatMap { it.members }.map { it.name }
        return coroutineScope {
            failed
                .map { name ->
                    async {
                        val kernelName = NodeNameResolver.resolve(kernelNames, name) ?: name
                        kernelName.takeIf {
                            kernelProxy.urlTestFailureKind(it, Constants.NODE_URLTEST_TIMEOUT_MS) ==
                                KernelUrlTestFailure.OFFLINE
                        }?.let { name } // 离线集合按订阅名存（与 NodeItem.offline 判断同口径）
                    }
                }.awaitAll()
                .filterNotNull()
                .toSet()
        }
    }

    /**
     * 把内核侧延迟表的 key（内核节点名，标准订阅带国旗前缀：`🇩🇪 [直]1德国`）
     * 映射回订阅/面板用的原始名（`[直]1德国`）。
     *
     * 两边名字对不上是"点测速跑完了但节点行全部停在「未测」"的直接根因——
     * 订阅列表与内核节点名只在装饰符号（国旗 emoji / 空白 / 全角）上有差别，
     * 用 [NodeNameResolver.resolve] 做同口径匹配即可对上；映射不上的 key 保留
     * 原样返回（不丢数据，调用方按名取不到就当没测过）。
     */
    internal fun remapDelayKeysToPanelNames(
        delays: Map<String, Int>,
        panelNames: List<String>,
    ): Map<String, Int> {
        if (delays.isEmpty() || panelNames.isEmpty()) return delays
        val out = LinkedHashMap<String, Int>(delays.size)
        for ((key, value) in delays) {
            out[NodeNameResolver.resolve(panelNames, key) ?: key] = value
        }
        return out
    }

    /**
     * [remapDelayKeysToPanelNames] 的反向：把订阅名 key 映射回内核名，供落盘使用
     * （speed_results 缓存的读取方 `withCachedDelays` / `refreshKernelInfo` 都按
     * 内核名取值）。映射不到的条目直接丢弃——留着也读不出来。
     */
    private fun remapDelayKeysToKernelNames(delays: Map<String, Int>): Map<String, Int> {
        if (delays.isEmpty()) return emptyMap()
        // 成员名去重：同一节点在多个组里重复出现会让 resolve 的 singleOrNull 判成歧义
        val kernelNames = _proxyGroups.value.flatMap { it.members }.map { it.name }.distinct()
        if (kernelNames.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>(delays.size)
        for ((key, value) in delays) {
            NodeNameResolver.resolve(kernelNames, key)?.let { out[it] = value }
        }
        return out
    }

    fun consumeSpeedTestTip() {
        _speedTestTipRes.value = null
    }

    /** 消费一次性的错误信号（首页 Toast 反馈用；节点页此前从不消费，故补这个出口）。 */
    fun consumeServerError() {
        _errorMessageRes.value = null
    }

    /**
     * 进入节点页时自动跑一次测速（整改要求 3）。
     *
     * **每个 App 会话只自动跑一次**：切 Tab 会重建节点页组合，若不节流，用户每切一次 Tab
     * 就触发一轮全量测速（结构组测速预算 22.5s），既浪费流量又频繁打断操作。
     * 需要新数据可点顶栏「测速」；日常延迟也由内核按 interval=300 自行拨测维护
     * （见 kernel-core native/config/routing/routing_table.go）。
     */
    fun autoSpeedTestOnNodesPage() {
        if (autoSpeedTestOnEnter) return
        // 无套餐时不打网络：也不让 startSpeedTest 顺势把"无套餐"写进 errorMessageRes，
        // 那会在用户刚进页面时就弹一条与本次操作无关的提示。
        // 注意顺序：门禁不通过**不能**置位，否则本会话再也不会自动测速。
        if (!hasPlan()) return
        // 已有一轮在跑（如连接后的自动测速）：此时置位等于把本次机会空耗掉，
        // startSpeedTest 会被 _isTestingAll 守卫直接挡回，用户整场都看不到自动测速。
        if (_isTestingAll.value) return
        autoSpeedTestOnEnter = true
        startSpeedTest()
    }

    fun updateSubscription() {
        if (!hasPlan()) {
            _errorMessageRes.value = R.string.dashboard_no_plan_tip
            return
        }
        loadNodes(force = true)
    }

    fun loadProxyGroups() {
        viewModelScope.launch { refreshGroups() }
    }

    /** 分流组名（排除内核保留组）：折叠键的来源。 */
    private fun routingGroupNames(): List<String> = _proxyGroups.value
        .map { it.name }
        .filterNot { it in com.slte.app.kernel.RoutingReservedNames }

    /**
     * 组列表变化后校正"已见过"集合：**只登记、不收起**（niuren 定制：默认全部展开）。
     *
     * 旧版这里是"首见即收起"，加上进节点页的 [collapseAllSections]（已删除），用户每次进
     * 节点页看到的是全收起卡片——测速结果全藏在折叠卡里，表现为"点了测速看不到延迟"。
     * 现在集合只记"见过哪些组"，收起与否完全由用户点击决定。
     *
     * 保护保持不变：
     * 1. 组列表为空时直接返回——首次进节点页 / 内核未就绪时 `_proxyGroups` 为空，不做任何事；
     * 2. **不做**"丢弃已不存在的组名"的裁剪（理由同前：单组瞬时查询失败不应抹掉用户偏好）。
     */
    private fun syncCollapsedSections(groupNames: List<String>) {
        if (groupNames.isEmpty()) return
        val firstSeen =
            (groupNames + com.slte.app.kernel.PRIMARY_SECTION_KEY).filterNot { it in seenSections }
        if (firstSeen.isEmpty()) return
        seenSections += firstSeen
        // 只在真的新增了"首见"时落盘，避免每次分组刷新都写偏好
        persistSections()
    }

    /**
     * 切换某个区块的展开/收起。集合内 = 收起，**所有区块一视同仁**（纯开关）。
     *
     * 旧版对分流组做"单开手风琴"：那是默认全收起时代的产物（每条分流组 include-all
     * 全部节点，全开会组合出「组数 × 节点数」行）。niuren 定制改为默认全展开后，手风琴
     * 语义正好反转（点收一条会把其余全收），体验错乱；且内置分流默认关闭，分流组常态
     * 为空，性能顾虑已不存在——降级为纯开关。主组卡本就不参与手风琴，行为不变。
     */
    fun toggleSection(name: String) {
        _collapsedSections.value = com.slte.app.kernel.toggleCollapsed(_collapsedSections.value, name)
        persistSections()
    }

    private suspend fun refreshGroups() {
        _isLoadingGroups.value = true
        try {
            // 不在这里读自建组名：用 init / profileLoaded 维护的缓存（见 [customGroupNames]）
            refreshPrimarySectionState(kernelProxy.proxyGroups())
        } finally {
            // 与 startSpeedTest / testGroup 同口径：proxyGroups() 抛出（取消 / binder 死亡）
            // 时标志必须复位，否则节点页会永久停在加载态（其余分支再也走不到）
            _isLoadingGroups.value = false
        }
    }

    /**
     * 读自建分流组名（只读、不触发清洗回写；见 [customGroupNames] 的注释）。
     *
     * 只在**初始化**与**内核重载成功**时调用，不在每次刷新里调用——避免把文件 IO 放进
     * 节点页刷新路径（线程模型见 [customGroupNames]）。
     *
     * 解析失败 → null：**不能**与「没有自建组」混为一谈——[com.slte.app.kernel.RoutingStateStore.load]
     * 在解析失败时会回退默认 state（见其 readRaw 的 getOrElse），靠空集合兜会把「读不出来」
     * 误判成「没有自建组」，于是叫「Ⓜ️ 微软」的自建组会被误改名。
     */
    private fun customGroupNamesFromStore(): Set<String>? {
        val state = kernelProxy.config.routingStateStore.readOrNull() ?: return null
        return state.custom.map { it.name }.toSet()
    }

    /**
     * 拉取组列表并把结果同步给折叠集合（避免各调用点各写一份）。
     */
    private suspend fun refreshPrimarySectionState(next: List<KernelProxyGroupInfo>) {
        syncCollapsedSections(next.map { it.name })
        _proxyGroups.value = withCachedDelays(next)
    }

    /**
     * 未经测速的成员延迟是「未测」（[Constants.DELAY_PENDING]）而不是「超时」，
     * 用上次测速的缓存回填这些成员，让策略组在测速前也能显示已知延迟。
     * 已测过且失败（[Constants.DELAY_TIMEOUT]）的成员同样用缓存覆盖——
     * 缓存里存的是历史有效值（见 KernelProxySpeed.storeableDelays）。
     */
    private fun withCachedDelays(groups: List<KernelProxyGroupInfo>): List<KernelProxyGroupInfo> {
        val cached = kernelProxy.cachedSpeedResults()?.takeIf { it.isNotEmpty() } ?: return groups
        return groups.map { group ->
            group.copy(
                members =
                group.members.map { member ->
                    val hit = cached[member.name] ?: return@map member
                    val unmeasured =
                        member.delay == null ||
                            member.delay <= Constants.DELAY_PENDING ||
                            member.delay >= Constants.DELAY_TIMEOUT
                    if (unmeasured) member.copy(delay = hit) else member
                },
            )
        }
    }

    /** 把测速结果合并进节点列表（纯函数，可单测）。 */
    internal fun mergeDelays(
        nodes: List<NodeItem>,
        delays: Map<String, Int>,
    ): List<NodeItem> = nodes.map { node ->
        val hit = delays[node.name] ?: return@map node
        node.copy(delay = if (hit >= Constants.DELAY_TIMEOUT) Constants.DELAY_TIMEOUT else hit)
    }

    /**
     * 把测速结果就地回填进策略组成员（不重查内核，与 [testGroup] 的写回同口径）。
     *
     * VPN 断开但内核服务进程还活着时，节点行来自 `_proxyGroups`（策略组成员），
     * 未连接测速结果只合进 [data.nodes] 的话行上看不到；组类型成员不在结果表里，
     * 天然跳过。
     */
    internal fun mergeDelaysIntoGroups(delays: Map<String, Int>) {
        if (delays.isEmpty() || _proxyGroups.value.isEmpty()) return
        // TCP 兜底测速的 key 是订阅名，组成员是内核名（带国旗前缀），需要同口径匹配；
        // 内核测速路径两边都是内核名，精确命中走快路。
        val keys = delays.keys.toList()
        _proxyGroups.value =
            _proxyGroups.value.map { group ->
                group.copy(
                    members =
                    group.members.map { member ->
                        val hit =
                            delays[member.name]
                                ?: NodeNameResolver.resolve(keys, member.name)?.let { delays[it] }
                        hit?.let { member.copy(delay = it) } ?: member
                    },
                )
            }
    }

    fun selectInGroup(
        groupName: String,
        proxyName: String,
    ) {
        viewModelScope.launch {
            if (kernelProxy.selectInGroup(groupName, proxyName)) {
                // 就地更新：必须走 refreshPrimarySectionState（带 withCachedDelays）——
                // 直接赋值为内核原始快照会把未参与本轮探测的成员延迟清成「未测」，
                // 表现为"在分流组里点一个节点，同组其它节点的延迟数字瞬间消失"。
                val next = kernelProxy.proxyGroups()
                refreshPrimarySectionState(next)
                refreshSpecialNodes()
            } else {
                _errorMessageRes.value = R.string.proxy_group_select_failed
            }
        }
    }

    fun testGroup(groupName: String) {
        if (_testingGroup.value != null) return
        // 与 startSpeedTest 同口径的门禁：无套餐时静默返回会让用户以为"点了没反应"
        if (!hasPlan()) {
            _errorMessageRes.value = R.string.dashboard_no_plan_tip
            return
        }
        viewModelScope.launch {
            _testingGroup.value = groupName
            try {
                val delays = kernelProxy.testGroup(groupName)
                // 内核没起时 testGroup 返回空表：此时必须给提示，否则就是
                // "点测速转了一下、什么都没变"（空表不会写回任何成员）
                if (delays.isEmpty()) {
                    _speedTestTipRes.value = R.string.server_speed_test_failed
                    return@launch
                }
                _proxyGroups.value =
                    _proxyGroups.value.map { group ->
                        if (group.name != groupName) {
                            group
                        } else {
                            group.copy(
                                members =
                                group.members.map { member ->
                                    delays[member.name]?.let { member.copy(delay = it) } ?: member
                                },
                            )
                        }
                    }
            } finally {
                // 与 startSpeedTest 同理：异常后残留会让整页测速按钮全部失效
                _testingGroup.value = null
            }
        }
    }
}

data class ServerData(
    val nodes: List<NodeItem> = emptyList(),
    val selectedNodeId: Int = 0,
    val isLoading: Boolean = false,

    val kernelFallbackDelay: Int? = null,

    val autoNode: String? = null,

    val fallbackNode: String? = null,

    val autoNodeCountryCode: String? = null,

    val fallbackNodeCountryCode: String? = null,
) {

    val autoDelay: Int?
        get() =
            nodes
                .asSequence()
                .mapNotNull { it.delay }
                // > DELAY_PENDING：未测（0）不能被当成 0ms 参与取最小
                // < DELAY_TIMEOUT：超时（999）不是有效延迟
                .filter { it > Constants.DELAY_PENDING && it < Constants.DELAY_TIMEOUT }
                .minOrNull()

    val fallbackDelay: Int?
        get() =
            kernelFallbackDelay ?: nodes
                .asSequence()
                .mapNotNull { it.delay }
                .filter { it > Constants.DELAY_PENDING && it < Constants.DELAY_TIMEOUT }
                .sorted()
                .toList()
                .getOrNull(1)
}

data class NodeItem(
    val id: Int = 0,
    val name: String,
    val countryCode: String = "XX",
    val type: String = "",
    val host: String = "",
    /** 服务器端口：未连接时的 TCP 握手测速要用（内核没起来，端口只能来自订阅数据）。 */
    val port: Int = 0,
    val delay: Int? = null,
    /**
     * 探测确认不可达（域名不存在 / 连接被拒 / 无路由）。
     *
     * 与"延迟超时"是两回事：超时可能只是一次抖动，这个标记只在 urlTest
     * 明确分类为 offline 时才置位，节点页据此显示离线角标。
     */
    val offline: Boolean = false,
)
