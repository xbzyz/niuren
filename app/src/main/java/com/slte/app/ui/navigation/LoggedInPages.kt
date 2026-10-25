// SPDX-FileCopyrightText: 2026 Polaris Contributors
// SPDX-License-Identifier: GPL-3.0-only

package com.slte.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slte.app.BuildConfig
import com.slte.app.ui.component.isSupportedTelegramUrl
import com.slte.app.ui.component.openExternalUrl
import com.slte.app.ui.component.rememberToast
import com.slte.app.ui.screen.about.AboutScreen
import com.slte.app.ui.screen.giftcard.GiftCardRedeemSheet
import com.slte.app.ui.screen.giftcard.GiftCardRedeemViewModel
import com.slte.app.ui.screen.invite.InviteScreen
import com.slte.app.ui.screen.invite.InviteViewModel
import com.slte.app.ui.screen.main.DashboardData
import com.slte.app.ui.screen.main.MainViewModel
import com.slte.app.ui.screen.main.ProxyModeSheet
import com.slte.app.ui.screen.notice.NoticeScreen
import com.slte.app.ui.screen.notice.NoticeViewModel
import com.slte.app.ui.screen.order.OrdersScreen
import com.slte.app.ui.screen.order.OrdersViewModel
import com.slte.app.ui.screen.plans.PlansScreen
import com.slte.app.ui.screen.plans.PlansViewModel
import com.slte.app.ui.screen.plans.PurchaseFlow
import com.slte.app.ui.screen.plans.PurchaseStep
import com.slte.app.ui.screen.plans.PurchaseViewModel
import com.slte.app.ui.screen.profile.LogoutConfirmSheet
import com.slte.app.ui.screen.profile.ProfileViewModel
import com.slte.app.ui.screen.server.ServerViewModel
import com.slte.app.ui.screen.settings.AppearanceMode
import com.slte.app.ui.screen.settings.AppearanceModeSheet
import com.slte.app.ui.screen.settings.ChangePasswordSheet
import com.slte.app.ui.screen.settings.ChangePasswordState
import com.slte.app.ui.screen.settings.LanguageMode
import com.slte.app.ui.screen.settings.LanguageModeSheet
import com.slte.app.ui.screen.settings.SettingsViewModel
import com.slte.app.ui.screen.settings.TunStackModeSheet
import com.slte.app.ui.screen.ticket.TicketScreen
import com.slte.app.ui.screen.ticket.TicketViewModel
import com.slte.app.ui.screen.traffic.TrafficViewModel
import com.slte.app.ui.v5.NavTab
import com.slte.app.ui.v5.screens.V5HomeScreen
import com.slte.app.ui.v5.screens.V5MeScreen
import com.slte.app.ui.v5.screens.V5NodesScreen
import com.slte.app.ui.v5.screens.V5RoutingRulesScreen
import com.slte.app.ui.v5.screens.V5SettingsScreen
import com.slte.app.ui.v5.screens.V5TrafficScreen
import com.slte.app.utils.LogExport

@Composable
internal fun OrdersPageContent(
    ordersViewModel: OrdersViewModel,
    purchaseViewModel: PurchaseViewModel,
    purchaseStep: PurchaseStep,
    onBack: () -> Unit,
    pendingPaymentTradeNo: String?,
    onPendingPaymentConsumed: () -> Unit,
) {
    // 进程重建恢复到本页时兜底首拉（正常进入由预加载负责）
    LaunchedEffect(Unit) { ordersViewModel.ensureLoaded() }
    OrdersScreen(
        onBack = onBack,
        onPay = { tradeNo -> purchaseViewModel.loadPaymentForOrder(tradeNo) },
        viewModel = ordersViewModel,
    )
    PurchaseFlow(
        step = purchaseStep,
        onSelectPeriod = purchaseViewModel::selectPeriod,
        onUpdateCoupon = purchaseViewModel::updateCouponCode,
        onVerifyCoupon = purchaseViewModel::verifyCoupon,
        onConfirmOrder = purchaseViewModel::showConfirmWarning,
        onCancelWarning = purchaseViewModel::cancelWarning,
        onConfirmWarning = purchaseViewModel::confirmWarning,
        onSelectPayment = purchaseViewModel::selectPaymentMethod,
        onConfirmPayment = purchaseViewModel::confirmPayment,
        onPaymentReturn = {
            purchaseViewModel.onPaymentReturn()
            ordersViewModel.refresh()
        },
        onDismiss = purchaseViewModel::goBack,
    )

    val payingTradeNo = (purchaseStep as? PurchaseStep.OrderPayment)?.tradeNo
    LaunchedEffect(payingTradeNo) {
        if (payingTradeNo != null) purchaseViewModel.startOrderPolling(payingTradeNo)
    }
    pendingPaymentTradeNo?.let { tradeNo ->
        LaunchedEffect(tradeNo) {
            purchaseViewModel.loadPaymentForOrder(tradeNo)
            onPendingPaymentConsumed()
        }
    }
}

@Composable
internal fun DashboardPageContent(
    mainViewModel: MainViewModel,
    // 首页节点选择（niuren 定制）：连接舞台的节点行可点开选择面板，未连接也能选节点、测真延迟。
    // 与节点页共享同一个实例（LoggedInViewModels.server），状态天然同步。
    serverViewModel: ServerViewModel,
    mainData: DashboardData,
    onRenew: () -> Unit,
    onNavSelect: (com.slte.app.ui.v5.NavTab) -> Unit,
) {
    var showProxyMode by rememberSaveable { mutableStateOf(false) }
    // 首页公告（niuren 定制）：跑马灯 + 最新未读弹窗，数据由 MainViewModel 统一拉取
    val homeNotices by mainViewModel.homeNotices.collectAsStateWithLifecycle()
    // 节点选择面板数据：进首页兜底拉一次（fetchServers 缓存优先，几乎零成本；
    // VM init 已用缓存+延迟回填过一份，这里只为拿到最新的订阅节点名单）
    LaunchedEffect(Unit) {
        serverViewModel.loadNodes()
        // 拉策略组：首页节点选择要拿真实主组名（"niuren"），否则 selectPrimaryMember
        // 回退字面量 "🚀 节点选择" patch 不到真实组 → "选不了节点"（真实反馈根因）。
        serverViewModel.loadProxyGroups()
    }
    val serverData by serverViewModel.data.collectAsStateWithLifecycle()
    val pendingNode by serverViewModel.pendingNode.collectAsStateWithLifecycle()
    val isTestingAll by serverViewModel.isTestingAll.collectAsStateWithLifecycle()
    // 首页节点选择 / 测速的反馈此前被静默（节点页也从未消费这两个流）。这里用 Toast
    // 把"选节点失败 / 测速完成 / 无套餐 / 已记住选择"等结果暴露出来，否则用户看到的就是
    // "点了节点没反应、点了测速没动静"（真实反馈根因之二）。
    val serverErrorRes by serverViewModel.errorMessageRes.collectAsStateWithLifecycle()
    val serverSpeedTipRes by serverViewModel.speedTestTipRes.collectAsStateWithLifecycle()
    val toast = rememberToast()
    LaunchedEffect(serverErrorRes) {
        serverErrorRes?.let {
            toast.show(it)
            serverViewModel.consumeServerError()
        }
    }
    LaunchedEffect(serverSpeedTipRes) {
        serverSpeedTipRes?.let {
            toast.show(it)
            serverViewModel.consumeSpeedTestTip()
        }
    }
    // 连接态下切节点成功（selectionAppliedTick 自增）→ 延时回读出口：首页节点行
    // 与选择面板勾选显示的是 DashboardData.serverName（info.node），只有
    // refreshKernelInfo 才更新——不刷的话内核切到位了界面还停在上一个节点，
    // 看起来就是"选了不生效"（真机反馈）。selectInGroup 内部已轮询回读确认，
    // 这里的小延时只为等它返回后 kernel 侧状态稳定。
    val selectionTick by serverViewModel.selectionAppliedTick.collectAsStateWithLifecycle()
    LaunchedEffect(selectionTick) {
        if (selectionTick > 0) {
            kotlinx.coroutines.delay(600)
            mainViewModel.refreshKernelInfo()
        }
    }
    V5HomeScreen(
        data = mainData,
        notices = homeNotices,
        onToggleConnection = mainViewModel::toggleConnection,
        onVpnPermissionDenied = mainViewModel::onVpnPermissionDenied,
        vpnRequestIntent = mainViewModel::vpnRequestIntent,
        onRenew = onRenew,
        onNavSelect = onNavSelect,
        refreshKernelInfo = mainViewModel::refreshKernelInfo,
        onProxyModeClick = { showProxyMode = true },
        nodes = serverData.nodes,
        pendingNodeName = pendingNode,
        isTestingAll = isTestingAll,
        // 主组切换语义（同节点页主卡）：内核活着即时生效，进程没起记 pending
        onPickNode = serverViewModel::selectPrimary,
        onStartSpeedTest = serverViewModel::startSpeedTest,
    )
    if (showProxyMode) {
        ProxyModeSheet(
            currentMode = mainData.proxyMode,
            onDismiss = { showProxyMode = false },
            onSelect = mainViewModel::setProxyMode,
        )
    }
}

@Composable
internal fun ProfilePageContent(
    profileViewModel: ProfileViewModel,
    onNotice: () -> Unit,
    onOrders: () -> Unit,
    onInvite: () -> Unit,
    onRenew: () -> Unit,
    onTickets: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onNavSelect: (com.slte.app.ui.v5.NavTab) -> Unit,
    // 礼品卡兑换：ViewModel 与 Sheet 组件在 v5 重写时被留成死代码，这里恢复挂载点。
    // 用无 key 的 hiltViewModel()（与设置页同款），实例落在 Activity store，切 Tab 不丢状态。
    giftCardViewModel: GiftCardRedeemViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { profileViewModel.refresh() }
    val data by profileViewModel.data.collectAsStateWithLifecycle()

    // 退出登录必须二次确认：v4 有 LogoutConfirmSheet，v5 迁移时把 onLogout 直接接到了
    // logout() 上，误触即掉线（且会连带清掉本地会话）。这里把确认弹层接回来。
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }

    val giftCardState by giftCardViewModel.state.collectAsStateWithLifecycle()
    val giftCardTip by giftCardViewModel.tip.collectAsStateWithLifecycle()
    val giftCardRedeemed by giftCardViewModel.redeemed.collectAsStateWithLifecycle()
    val toast = rememberToast()

    LaunchedEffect(giftCardTip) {
        giftCardTip?.messageRes?.let { toast.show(it) }
        if (giftCardTip != null) giftCardViewModel.clearTip()
    }
    // 兑换成功会改变余额/流量/到期，必须回拉个人中心，否则用户看到的是旧数据
    LaunchedEffect(giftCardRedeemed) {
        if (giftCardRedeemed > 0) profileViewModel.refresh()
    }

    // Telegram 讨论组入口：面板下发的链接必须过白名单（http(s) + t.me 等域名），
    // 否则被劫持的面板可以下发 intent:// / file:// 做任意组件启动或本地文件外泄。
    // 面板没配置时退回编译期常量 BuildConfig.TELEGRAM_GROUP_URL（v4 的兜底语义）。
    // 两者都拿不到就不渲染该行——不做一个点了没反应的死入口。
    val context = LocalContext.current
    val telegramUrl: String? =
        data.telegramDiscussLink?.takeIf { isSupportedTelegramUrl(it) }
            ?: BuildConfig.TELEGRAM_GROUP_URL.takeIf { isSupportedTelegramUrl(it) }
    val onTelegramClick: (() -> Unit)? =
        telegramUrl?.let { url ->
            {
                openExternalUrl(context, url)
                Unit
            }
        }

    V5MeScreen(
        data = data,
        onPlans = onRenew,
        onGiftCard = giftCardViewModel::open,
        onOrders = onOrders,
        onInvite = onInvite,
        onTickets = onTickets,
        onNotices = onNotice,
        onSettings = onSettings,
        onAbout = onAbout,
        onLogout = { showLogoutConfirm = true },
        onNavSelect = onNavSelect,
        onTelegram = onTelegramClick,
    )

    if (showLogoutConfirm) {
        LogoutConfirmSheet(
            onConfirm = {
                showLogoutConfirm = false
                profileViewModel.logout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    if (giftCardState.visible) {
        GiftCardRedeemSheet(
            state = giftCardState,
            onCodeChange = giftCardViewModel::updateCode,
            onSubmit = giftCardViewModel::submit,
            onDismiss = giftCardViewModel::dismiss,
        )
    }
}

@Composable
internal fun ServerPageContent(
    serverViewModel: ServerViewModel,
    onUpdateSubscription: () -> Unit,
    onNavSelect: (com.slte.app.ui.v5.NavTab) -> Unit,
) {
    LaunchedEffect(Unit) {
        serverViewModel.loadNodes()
        // 策略组（含分流规则组的出口）来自运行中的内核，进入节点页即拉一次
        serverViewModel.loadProxyGroups()
        // 折叠默认全展开（niuren 定制）：不再进页全收起——旧版全收起把测速结果
        // 藏进折叠卡里，用户表现为"点了测速看不到延迟"。
    }
    val data by serverViewModel.data.collectAsStateWithLifecycle()
    val groups by serverViewModel.proxyGroups.collectAsStateWithLifecycle()
    val isLoadingGroups by serverViewModel.isLoadingGroups.collectAsStateWithLifecycle()
    val testingGroup by serverViewModel.testingGroup.collectAsStateWithLifecycle()
    val isTestingAll by serverViewModel.isTestingAll.collectAsStateWithLifecycle()
    // 折叠态放 ViewModel：切 Tab 会销毁本屏组合，屏幕内 remember/rememberSaveable 都会丢
    val collapsedSections by serverViewModel.collapsedSections.collectAsStateWithLifecycle()
    // 显示名映射要放过用户自建组（见 groupDisplayName）；只影响标题文本
    val customGroupNames by serverViewModel.customGroupNames.collectAsStateWithLifecycle()
    // 未连接预选（niuren 定制）：内核进程没起时点了节点先记在 pending，勾选高亮跟随它
    val pendingNode by serverViewModel.pendingNode.collectAsStateWithLifecycle()
    // 节点页的一次性事件（选节点失败 / 测速提示）此前从不消费，Shared VM 里的
    // 旧事件会一直挂着，等用户切回首页才被弹出来——与当前页面完全无关。
    // 这里与首页同样消费：谁可见谁弹。
    val nodesErrorRes by serverViewModel.errorMessageRes.collectAsStateWithLifecycle()
    val nodesSpeedTipRes by serverViewModel.speedTestTipRes.collectAsStateWithLifecycle()
    val nodesToast = rememberToast()
    LaunchedEffect(nodesErrorRes) {
        nodesErrorRes?.let {
            nodesToast.show(it)
            serverViewModel.consumeServerError()
        }
    }
    LaunchedEffect(nodesSpeedTipRes) {
        nodesSpeedTipRes?.let {
            nodesToast.show(it)
            serverViewModel.consumeSpeedTestTip()
        }
    }
    V5NodesScreen(
        data = data,
        groups = groups,
        isLoadingGroups = isLoadingGroups,
        testingGroup = testingGroup,
        isTestingAll = isTestingAll,
        // 节点选择卡是主组切换：走 selectPrimary（额外同步全局模式下的 GLOBAL），
        // 分流规则组仍走 selectInGroup（不应改 GLOBAL）。
        onSelectPrimary = serverViewModel::selectPrimary,
        onSelectInGroup = serverViewModel::selectInGroup,
        onTestGroup = serverViewModel::testGroup,
        onStartSpeedTest = serverViewModel::startSpeedTest,
        // 更新订阅：除首页/订阅流程外，还要让本 VM 用新缓存重填节点列表——
        // 否则节点页点完刷新转圈，列表与分组纹丝不动（切走再切回才更新）。
        onRefreshSubscription = {
            onUpdateSubscription()
            serverViewModel.refreshNodesFromCache()
        },
        onNavSelect = onNavSelect,
        collapsedSections = collapsedSections,
        onToggleSection = serverViewModel::toggleSection,
        customGroupNames = customGroupNames,
        // 首次进入节点页自动测速一次：不这样做节点行永远停在「未测」，用户无从判断
        // "自动选择"凭什么选中某个节点（整改要求 3）。节流在 VM 内（每会话一次）。
        onAutoTestOnce = serverViewModel::autoSpeedTestOnNodesPage,
        pendingNodeName = pendingNode,
    )
}

@Composable
internal fun InvitePageContent(
    inviteViewModel: InviteViewModel,
    onBack: () -> Unit,
) {
    // 进程重建恢复到本页时兜底首拉（正常进入由预加载负责）
    LaunchedEffect(Unit) { inviteViewModel.ensureLoaded() }
    InviteScreen(
        onBack = onBack,
        viewModel = inviteViewModel,
    )
}

@Composable
internal fun NoticePageContent(
    noticeViewModel: NoticeViewModel,
    onBack: () -> Unit,
) {
    // 进程重建恢复到本页时兜底首拉（正常进入由预加载负责）
    LaunchedEffect(Unit) { noticeViewModel.ensureLoaded() }
    NoticeScreen(
        onBack = onBack,
        viewModel = noticeViewModel,
    )
}

@Composable
internal fun TicketPageContent(
    ticketViewModel: TicketViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { ticketViewModel.enterAndRefresh() }
    TicketScreen(
        onBack = onBack,
        viewModel = ticketViewModel,
    )
}

@Composable
internal fun PlansPageContent(
    plansViewModel: PlansViewModel,
    purchaseViewModel: PurchaseViewModel,
    onBack: () -> Unit,
    onGoToOrders: () -> Unit,
) {
    // 进程重建恢复到本页时兜底首拉（正常进入由预加载负责）
    LaunchedEffect(Unit) { plansViewModel.ensureLoaded() }
    PlansScreen(
        onBack = onBack,
        viewModel = plansViewModel,
        purchaseViewModel = purchaseViewModel,
        onGoToOrders = onGoToOrders,
    )
}

@Composable
internal fun SettingsPageContent(
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showTunStack by rememberSaveable { mutableStateOf(false) }
    V5SettingsScreen(
        viewModel = viewModel,
        onBack = onBack,
        onAppearance = { showAppearance = true },
        onLanguage = { showLanguage = true },
        onTunStack = { showTunStack = true },
        onChangePassword = viewModel::showChangePassword,
    )
    if (showAppearance) {
        AppearanceModeSheet(
            currentMode = AppearanceMode.fromThemeMode(viewModel.themeMode.collectAsStateWithLifecycle().value),
            onDismiss = { showAppearance = false },
            onSelect = {
                viewModel.setThemeMode(it.mode)
                showAppearance = false
            },
        )
    }
    if (showLanguage) {
        LanguageModeSheet(
            currentMode = LanguageMode.fromLocale(viewModel.data.collectAsStateWithLifecycle().value.locale),
            onDismiss = { showLanguage = false },
            onSelect = {
                viewModel.setLocale(it.locale)
                showLanguage = false
            },
        )
    }
    if (showTunStack) {
        TunStackModeSheet(
            currentMode = viewModel.data.collectAsStateWithLifecycle().value.tunStackMode,
            onDismiss = { showTunStack = false },
            onSelect = {
                viewModel.setTunStackMode(it)
                showTunStack = false
            },
        )
    }
    val changePasswordState = viewModel.changePasswordState.collectAsStateWithLifecycle().value
    val editing = changePasswordState as? ChangePasswordState.Editing
    if (editing != null) {
        ChangePasswordSheet(
            state = editing,
            onOldPasswordChange = viewModel::onOldPasswordChange,
            onNewPasswordChange = viewModel::onNewPasswordChange,
            onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
            onSubmit = viewModel::submitChangePassword,
            onDismiss = viewModel::dismissChangePassword,
        )
    }
}

@Composable
internal fun RoutingRulesPageContent(onBack: () -> Unit) {
    V5RoutingRulesScreen(onBack = onBack)
}

@Composable
internal fun AboutPageContent(onBack: () -> Unit) {
    AboutScreen(onBack = onBack)
}

@Composable
internal fun TrafficPageContent(
    trafficViewModel: TrafficViewModel,
    onNavSelect: (com.slte.app.ui.v5.NavTab) -> Unit,
) {
    // 流量页用的是 Activity 级 ViewModel（切 Tab 不重建），只在 init 里拉过一次；
    // 不在这里主动拉，用户切走再切回看到的永远是进 App 那一刻的旧数据。
    LaunchedEffect(Unit) { trafficViewModel.load() }
    val data by trafficViewModel.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    V5TrafficScreen(
        data = data,
        onNavSelect = onNavSelect,
        onRetry = trafficViewModel::load,
        // 失败时一键导出诊断（含后端类型/面板），用户不必先跑到「我的 → 关于」再复述问题。
        onExportDiagnostics = { LogExport.exportAndShare(context, trafficViewModel.diagnosticsExtra()) },
    )
}
