package app.toolkit.ui.netguard

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.toolkit.R
import app.toolkit.netguard.AppIconCache
import app.toolkit.netguard.InstalledApp
import app.toolkit.netguard.NetGuardViewModel
import app.toolkit.ui.components.ToolkitTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetGuardScreen(
    modifier: Modifier = Modifier,
    viewModel: NetGuardViewModel = viewModel(),
    onOpenPrivilege: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchActive by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // 切换应用类型 / 搜索词后，列表内容整体变了，直接回顶部而不是停在原位置
    LaunchedEffect(state.showSystemApps, state.showUserApps, state.query) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }
    // 离开页面时清空搜索，避免下次进来列表被过滤却没有输入框
    DisposableEffect(Unit) {
        onDispose { viewModel.onQueryChange("") }
    }

    // 从特权页或系统设置返回时重新读取规则状态。
    // 首次 ON_RESUME 与 ViewModel init 的刷新重复，跳过以免进页面就加载两次。
    var firstResume by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (firstResume) {
            firstResume = false
        } else {
            viewModel.refresh(showLoading = false)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ToolkitTopBar(
                title = stringResource(R.string.netguard_title),
                subtitle = stringResource(
                    R.string.netguard_subtitle,
                    state.blocked.size,
                    state.apps.size,
                ),
                titleContent = if (searchActive) {
                    {
                        BasicTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (state.query.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.netguard_search_hint),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                } else {
                    null
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (searchActive) viewModel.onQueryChange("")
                            searchActive = !searchActive
                        },
                    ) {
                        Icon(
                            imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.netguard_search_hint),
                        )
                    }
                    IconButton(onClick = onOpenPrivilege) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = stringResource(R.string.tab_privilege),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.action_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            TypeFilterItem(
                                label = stringResource(R.string.type_system_apps),
                                checked = state.showSystemApps,
                                // 只剩它一个勾选时禁止取消，避免列表空掉
                                enabled = !(state.showSystemApps && !state.showUserApps),
                                onCheckedChange = viewModel::setShowSystemApps,
                            )
                            TypeFilterItem(
                                label = stringResource(R.string.type_user_apps),
                                checked = state.showUserApps,
                                enabled = !(state.showUserApps && !state.showSystemApps),
                                onCheckedChange = viewModel::setShowUserApps,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 过滤 + 排序只在相关状态变化时重算，避免每次重组都排一遍
            val visibleApps = remember(
                state.apps,
                state.blocked,
                state.query,
                state.showSystemApps,
                state.showUserApps,
            ) {
                state.visibleApps
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(
                    // 左右留白只由 contentPadding 提供，避免与外层 padding 叠加成 32dp
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    // 与主页列表一致：左右 16dp、上下 12dp，条目间距 12dp
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = visibleApps,
                        key = { it.packageName },
                        // 按开关状态拆开复用池：Switch 的拇指位移动画状态在节点复用时不会被重置，
                        // 把已开启行的数据复用给未开启行的节点时，会从上一行的位置动画过来，
                        // 看起来就是"已经开着的那行从关滑到开"
                        contentType = { app ->
                            if (app.packageName in state.blocked) "app-blocked" else "app-unblocked"
                        },
                    ) { app ->
                        AppRow(
                            app = app,
                            blocked = app.packageName in state.blocked,
                            // 只禁用本次操作涉及的行；其它行的 enabled 保持不变，
                            // 否则一次切换会让整个列表的开关一起变灰再恢复。
                            enabled = state.privilegedReady &&
                                app.packageName !in state.applyingPackages,
                            onToggle = { block -> viewModel.toggle(app.packageName, block) },
                        )
                    }
                }
            }
        }
    }

}

/**
 * 菜单内的应用类型筛选行：文案在左，勾选框在右。
 * 点击整行与点击勾选框等效，菜单不关闭，方便连续切换。
 */
@Composable
private fun TypeFilterItem(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        },
        onClick = { if (enabled) onCheckedChange(!checked) },
        enabled = enabled,
        trailingIcon = {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun AppRow(
    app: InstalledApp,
    blocked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        // 与主页 FeatureCard 保持一致：默认圆角 + surfaceContainerLow 底色
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(app.packageName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = blocked,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    // 命中缓存时用同步值做初始值，否则首帧会先闪一次占位图标
    val bitmap by produceState<ImageBitmap?>(initialValue = AppIconCache.peek(packageName), packageName) {
        value = AppIconCache.load(context, packageName)
    }
    val shown = bitmap
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Wifi,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
