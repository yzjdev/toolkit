package app.toolkit.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** 邮件模块内部的二级页面。收件箱是根，账号与设置从顶栏进入。 */
private enum class MailPage { INBOX, ACCOUNTS, SETTINGS }

/**
 * 邮件页：统一收件箱 + 账号管理 + 同步设置。
 *
 * 底部导航只留一个「邮件」入口，账号与设置作为内部二级页面，
 * 避免和禁网/温度/ADB 这些互不相关的工具挤在同一层。
 * 邮件详情覆盖整个模块区域，返回手势先关详情、再退二级页、最后才交给外层。
 */
@Composable
fun MailScreen(
    modifier: Modifier = Modifier,
    viewModel: MailViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncingIds by viewModel.syncingIds.collectAsStateWithLifecycle()
    val uiMessage by viewModel.message.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val syncCount by viewModel.syncCount.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(MailPage.INBOX) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 提示统一走 Snackbar，显示后清空，避免旋转屏幕重复弹出
    LaunchedEffect(uiMessage) {
        val msg = uiMessage ?: return@LaunchedEffect
        val text = when (msg) {
            is UiMessage.Info -> msg.text
            is UiMessage.Error -> msg.text
        }
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(text)
    }

    // 详情页优先接管返回：先关详情，再退二级页
    val current = detail
    if (current != null) {
        BackHandler { viewModel.closeMessage() }
        MailDetailScreen(
            mail = current,
            onBack = { viewModel.closeMessage() },
        )
        return
    }

    if (page != MailPage.INBOX) {
        // 二级页面：返回手势回到收件箱，而不是退出应用
        BackHandler { page = MailPage.INBOX }
    }

    Scaffold(
        // 页面内部自带 Scaffold + TopAppBar，这里不再消费系统栏安全区，避免叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            when (page) {
                MailPage.INBOX -> InboxScreen(
                    messages = messages,
                    accounts = accounts,
                    filter = filter,
                    loading = loading,
                    syncing = syncing,
                    onQueryChange = viewModel::setQuery,
                    onAccountFilter = viewModel::setAccountFilter,
                    onSync = viewModel::syncAll,
                    onOpen = { mail -> viewModel.openMessage(mail) },
                    onOpenAccounts = { page = MailPage.ACCOUNTS },
                    onOpenSettings = { page = MailPage.SETTINGS },
                )

                MailPage.ACCOUNTS -> AccountsScreen(
                    accounts = accounts,
                    syncingIds = syncingIds,
                    onSave = viewModel::saveAccount,
                    onDelete = viewModel::deleteAccount,
                    onSync = viewModel::syncOne,
                    onBack = { page = MailPage.INBOX },
                )

                MailPage.SETTINGS -> SettingsScreen(
                    syncCount = syncCount,
                    onSaveSyncCount = viewModel::setSyncCount,
                    onBack = { page = MailPage.INBOX },
                )
            }
        }
    }
}
