package app.toolkit.ui.lanshare

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.toolkit.R
import app.toolkit.lanshare.AccessCode
import app.toolkit.lanshare.ExternalStorage
import app.toolkit.lanshare.LanShareAccessCode
import app.toolkit.lanshare.LanShareLevel
import app.toolkit.lanshare.LanShareViewModel
import app.toolkit.ui.components.ToolkitTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanShareScreen(
    modifier: Modifier = Modifier,
    viewModel: LanShareViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Android 10 及以下用运行时权限；11+ 只能跳系统设置页，走下面的 openSettings
    val requestLegacy = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermission() }

    // 从系统设置页返回：重新读一次权限状态，顺便重算服务地址
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermission()
        viewModel.refreshAddress()
    }

    // 日志倒序展示：最新的在最上面，不用滚到底部就能看到刚发生的事。
    // 只在条数变化时重算，避免每次重组都翻一遍列表。
    val reversedLogs = remember(logs) { logs.asReversed() }

    // 用户停在顶部时才跟随最新一条；正在翻看历史时不去抢滚动位置
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && listState.firstVisibleItemIndex == 0) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ToolkitTopBar(
                title = stringResource(R.string.lanshare_title),
                // 副标题直接放访问地址：这是用户真正要用的东西，
                // 「服务运行中」这种状态开关本身就是指示，不必再占一行
                subtitle = when {
                    state.running -> state.url ?: stringResource(R.string.lanshare_subtitle_running)
                    state.starting -> stringResource(R.string.lanshare_starting)
                    else -> stringResource(R.string.lanshare_subtitle_stopped)
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.lanshare_clear_logs),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // 头部卡片固定、只有日志滚动：开关不会随着日志一起滚出屏幕。
        // 授权码搬去设置页之后，头部只剩地址卡（和缺权限时的提示卡），
        // 高度可控，日志区能稳定拿到剩余空间。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!state.hasPermission) {
                PermissionCard(
                    onRequest = {
                        if (ExternalStorage.usesRuntimePermission()) {
                            requestLegacy.launch(ExternalStorage.runtimePermissions())
                        } else {
                            openAllFilesSettings(context)
                        }
                    },
                )
            }

            AddressCard(
                running = state.running,
                starting = state.starting,
                rootLabel = state.rootLabel,
                enabled = state.hasPermission,
                onToggle = viewModel::toggle,
                accessCode = state.accessCode,
                onEnabledChange = viewModel::setAccessCodeEnabled,
                onCodeChange = viewModel::setAccessCodeValue,
                onRandom = viewModel::randomizeAccessCode,
            )

            Text(
                text = stringResource(R.string.lanshare_log_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            )

            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.lanshare_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(reversedLogs, key = { it.id }) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = entry.time,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (entry.level) {
                                    LanShareLevel.ERROR -> MaterialTheme.colorScheme.error
                                    LanShareLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                    LanShareLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                                    LanShareLevel.INFO -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 权限卡片：没有「所有文件访问权限」时服务根本起不来，
 * 所以它不是提示而是一个必须先处理的前置条件，独占一块醒目位置。
 */
@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.lanshare_permission_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.lanshare_permission_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onRequest,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.lanshare_permission_grant))
            }
        }
    }
}

/**
 * 服务卡片：开关、传输目录与访问授权码。
 *
 * 访问地址在顶栏副标题，这里第二行只留「文件夹图标 + 路径」说明共享的是哪个目录，
 * 授权码紧跟在路径下方——它和目录一样，都是「谁能访问、能访问什么」的答案。
 */
@Composable
private fun AddressCard(
    running: Boolean,
    starting: Boolean,
    rootLabel: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    accessCode: AccessCode,
    onEnabledChange: (Boolean) -> Unit,
    onCodeChange: (String) -> Unit,
    onRandom: () -> Unit,
) {
    // 输入了但还不是 4 位数字：不拦住保存（用户可能还没输完），只提示
    val incomplete = accessCode.code.isNotEmpty() && !LanShareAccessCode.isValid(accessCode.code)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Text(
                        text = if (running) {
                            stringResource(R.string.lanshare_service_on)
                        } else {
                            stringResource(R.string.lanshare_service_off)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // 目录就放在原来那句描述的位置：只给图标和路径，
                    // 不再重复「同一局域网内的设备都能读写这个目录」——
                    // 用户要看的是具体路径，说明性文字看过一次就够了
                    if (rootLabel.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = rootLabel,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    } else {
                        Text(
                            text = when {
                                running -> stringResource(R.string.lanshare_service_on_desc)
                                starting -> stringResource(R.string.lanshare_starting)
                                else -> stringResource(R.string.lanshare_service_off_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 三句描述换行行数不一样，锁两行避免卡片上下抖
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 开关常驻，不再和转圈互相 Crossfade：一次点击要先把开关淡出换成转圈、
                // 启动完成后再淡回来，两次淡入淡出叠在一起就是肉眼看到的闪。
                Switch(
                    checked = running || starting,
                    // 启动过程中忽略点击：start() 自己有并发保护，这里只是避免开关来回弹
                    onCheckedChange = { if (!starting) onToggle() },
                    enabled = enabled || running || starting,
                )
            }

            // 授权码紧跟在路径下方：它和目录是同一个问题的两面。
            // 服务没开时这里没有任何意义（没人能访问），整块收起而不是留个开关在那。
            AnimatedVisibility(
                visible = running,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(durationMillis = 160, delayMillis = 30)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(durationMillis = 100)),
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.lanshare_code_title),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    if (accessCode.enabled) {
                                        R.string.lanshare_code_desc_on
                                    } else {
                                        R.string.lanshare_code_desc_off
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = accessCode.enabled,
                            onCheckedChange = onEnabledChange,
                        )
                    }

                    AnimatedVisibility(
                        visible = accessCode.enabled,
                        enter = expandVertically(
                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top,
                        ) + fadeIn(animationSpec = tween(durationMillis = 140, delayMillis = 30)),
                        exit = shrinkVertically(
                            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top,
                        ) + fadeOut(animationSpec = tween(durationMillis = 100)),
                    ) {
                        OutlinedTextField(
                            value = accessCode.code,
                            onValueChange = onCodeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            label = { Text(stringResource(R.string.lanshare_code_label)) },
                            placeholder = { Text(stringResource(R.string.lanshare_code_placeholder)) },
                            singleLine = true,
                            isError = incomplete,
                            supportingText = if (incomplete) {
                                { Text(stringResource(R.string.lanshare_code_too_short)) }
                            } else {
                                null
                            },
                            // 只收数字：授权码固定 4 位，限制输入类型省得用户切输入法
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            // 等宽字体：授权码是要照着念/抄的，字符宽度一致更不容易看错
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp,
                            ),
                            // 尾部是随机生成而不是复制：这个码本来就要念给对方，
                            // 复制到自己剪贴板没什么用，一键换一个更实用。
                            // 换完码输入框里直接就是新值，不需要再弹提示。
                            trailingIcon = {
                                IconButton(onClick = onRandom) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = stringResource(R.string.lanshare_code_random),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 跳「所有文件访问权限」系统设置页。
 *
 * 优先应用专属页面；部分 OEM 裁掉了这个 Activity，退回全局列表页，
 * 再失败就提示用户手动去设置里找。
 */
private fun openAllFilesSettings(context: Context) {
    val intents = ExternalStorage.settingsIntents(context)
    for (intent in intents) {
        val opened = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (opened) return
    }
    Toast.makeText(context, R.string.lanshare_permission_manual, Toast.LENGTH_LONG).show()
}
