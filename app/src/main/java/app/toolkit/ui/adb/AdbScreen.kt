package app.toolkit.ui.adb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.toolkit.R
import app.toolkit.adb.AdbCompletionEngine
import app.toolkit.adb.AdbLogEntry
import app.toolkit.adb.AdbSuggestion
import app.toolkit.adb.AdbSuggestionKind
import app.toolkit.adb.AdbViewModel
import app.toolkit.ui.components.ToolkitTopBar

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/** 命令输入框高度：单行但保留舒适的点击区域 */
private val CommandFieldHeight = 56.dp

/** 输入框与运行按钮共用的圆角，保证两者外形一致 */
private val FieldCorner = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(
    modifier: Modifier = Modifier,
    viewModel: AdbViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }

    // 最新一条排在最上方，新记录出现时滚回顶部
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ToolkitTopBar(
                title = stringResource(R.string.adb_title),
                subtitle = if (state.connected) {
                    // 优先显示设备地址，缺失时用未知占位
                    stringResource(
                        R.string.adb_connected,
                        state.connectedTarget ?: stringResource(R.string.adb_unknown_target),
                    )
                } else {
                    stringResource(R.string.adb_disconnected)
                },
                actions = {
                    // 断开常驻标题栏，仅已连接时出现
                    if (state.connected) {
                        IconButton(
                            onClick = viewModel::disconnect,
                            enabled = !state.busy,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LinkOff,
                                contentDescription = stringResource(R.string.adb_menu_disconnect),
                            )
                        }
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.adb_clear)) },
                                onClick = {
                                    viewModel.clear()
                                    menuOpen = false
                                },
                                enabled = state.entries.isNotEmpty() && !state.busy,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CommandInput(
                value = state.command,
                busy = state.busy,
                onValueChange = viewModel::onCommandChange,
                onSubmit = viewModel::submit,
                onAbort = viewModel::abort,
            )

            HorizontalDivider()

            if (state.entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Adb,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.adb_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 倒序展示：最新的一条在最上方
                    val newestId = state.entries.lastOrNull()?.id
                    items(items = state.entries.asReversed(), key = { it.id }) { entry ->
                        EntryCard(entry = entry, defaultExpanded = entry.id == newestId)
                    }
                }
            }
        }
    }
}

/**
 * 唯一的输入入口：Material 3 的 autocomplete 输入框 + 执行/中止按钮。
 *
 * 候选列表由官方 [ExposedDropdownMenuBox] 渲染成 Popup 浮层，
 * 不参与页面布局，因此展开/收起都不会顶开下方的日志列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandInput(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAbort: () -> Unit,
) {
    // 需要知道光标位置才能补全「正在输入的那个词」，因此内部用 TextFieldValue
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    // 只有输入框持有焦点时才展示补全，失焦后收起
    var focused by remember { mutableStateOf(false) }

    // 收起标记：记住「已经收起时输入框的内容」。
    // 内容一变（用户继续打字）就自动重新展开，选中候选后也能立即收起。
    var dismissedFor by remember { mutableStateOf<String?>(null) }

    // 长按候选时展示用法弹窗的候选；null 表示不展示
    var usageFor by remember { mutableStateOf<AdbSuggestion?>(null) }

    // 提交后 ViewModel 会清空命令，这里同步一次，避免输入框残留
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    val suggestions = remember(fieldValue.text, fieldValue.selection.start) {
        AdbCompletionEngine.suggest(fieldValue.text, fieldValue.selection.start)
    }
    // 空输入时也展示所有可用命令；失焦、执行中或已收起则不展示
    val expanded = focused && !busy && suggestions.isNotEmpty() && dismissedFor != fieldValue.text

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { wanted -> dismissedFor = if (wanted) null else fieldValue.text },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    onValueChange(it.text)
                },
                // PrimaryEditable：输入框本身既负责输入，也作为浮层的锚点
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        focused = it.isFocused
                        // 失焦时清掉收起标记，重新聚焦后候选能再次展开
                        if (!it.isFocused) dismissedFor = null
                    }
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = !busy),
                enabled = !busy,
                textStyle = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                shape = RoundedCornerShape(FieldCorner),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                placeholder = {
                    Text(
                        text = stringResource(R.string.adb_command_hint),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { dismissedFor = fieldValue.text },
                shape = RoundedCornerShape(FieldCorner),
            ) {
                suggestions.forEach { suggestion ->
                    // DropdownMenuItem 不支持长按，这里按 M3 规格复刻同一行：
                    // 最小高度 48dp + 水平 16dp 内边距，外观与原菜单项一致。
                    // 注意 heightIn 不能省——漏掉它行高会被压扁，列表会显得错乱。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .combinedClickable(
                                onClick = {
                                    fieldValue = TextFieldValue(
                                        suggestion.text,
                                        selection = TextRange(suggestion.text.length),
                                    )
                                    onValueChange(suggestion.text)
                                    // 选中后先收起，继续输入时再按新内容展开
                                    dismissedFor = suggestion.text
                                },
                                // 长按查看完整用法
                                onLongClick = { usageFor = suggestion },
                            )
                            .padding(ExposedDropdownMenuDefaults.ItemContentPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = suggestion.label,
                            style = MonoStyle,
                            color = if (suggestion.kind == AdbSuggestionKind.HINT) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (suggestion.description.isNotBlank()) {
                            Text(
                                text = suggestion.description,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 必须带参数的命令在右侧给出提示，避免选中后直接回车却报缺参数
                        if (suggestion.requiresArgument) {
                            Text(
                                text = stringResource(R.string.adb_requires_arg),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // 长按候选弹出的用法说明
        usageFor?.let { suggestion ->
            AlertDialog(
                onDismissRequest = { usageFor = null },
                title = { Text(text = suggestion.label, style = MonoStyle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // usage 为空时退回候选本身填入的命令，保证弹窗永远有内容
                        SelectionContainer {
                            Text(
                                text = suggestion.usage.ifBlank { suggestion.text },
                                style = MonoStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (suggestion.description.isNotBlank()) {
                            Text(
                                text = suggestion.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { usageFor = null }) {
                        Text(stringResource(R.string.adb_close))
                    }
                },
            )
        }

        // 运行 / 中止按钮：与输入框同高、圆角一致
        FilledIconButton(
            onClick = if (busy) onAbort else onSubmit,
            modifier = Modifier.size(CommandFieldHeight),
            shape = RoundedCornerShape(FieldCorner),
            enabled = if (busy) true else fieldValue.text.isNotBlank(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (busy) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (busy) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ),
        ) {
            Icon(
                imageVector = if (busy) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (busy) R.string.adb_abort else R.string.adb_run,
                ),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 单条操作结果卡片：标题 + 状态图标 + 复制按钮，展开后显示输出。
 *
 * [defaultExpanded] 用于让最新一条默认展开，历史记录保持折叠。
 * 用户手动点击后以点击结果为准，不再受该默认值影响。
 */
@Composable
private fun EntryCard(
    entry: AdbLogEntry,
    defaultExpanded: Boolean = false,
) {
    val context = LocalContext.current

    // 折叠状态放在卡片层：标题行与输出块都读同一份状态
    val lines = remember(entry.detail) { entry.detail.trimEnd().lines() }
    // 只要有输出就折叠，不按长度判断；执行中不折叠
    val collapsible = entry.detail.isNotBlank() && !entry.running
    // 同时以 defaultExpanded 为 key：新记录出现后，原先最新的一条会自动收回折叠，
    // 保证始终只有最近一条是展开的；用户在同一轮内手动点击仍能生效
    var expanded by remember(entry.id, defaultExpanded) { mutableStateOf(defaultExpanded) }

    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    val onToggle = { expanded = !expanded }

    // 可折叠时用带 onClick 的 Card：涟漪由 Card 内部按圆角裁剪，不会露出直角
    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusIcon(entry)
                Text(
                    text = entry.title,
                    modifier = Modifier.weight(1f),
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 可折叠时在标题行右侧给出方向指示
                if (collapsible) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = {
                        copyToClipboard(context, entry.detail.ifBlank { entry.title })
                        Toast.makeText(context, R.string.adb_copied, Toast.LENGTH_SHORT).show()
                    },
                    enabled = !entry.running,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.adb_copy),
                    )
                }
            }

            when {
                entry.running -> Text(
                    text = stringResource(R.string.adb_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 默认只显示标题行；展开后才渲染输出
                expanded && entry.detail.isNotBlank() -> OutputBlock(
                    lines = lines,
                    success = entry.success,
                )
            }
        }
    }

    if (collapsible) {
        Card(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            colors = cardColors,
            content = content,
        )
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = cardColors,
            content = content,
        )
    }
}

/**
 * 命令输出块。
 *
 * 只在展开时渲染：折叠状态下卡片只显示标题行，不占任何输出空间。
 * 无论输出多短都默认折叠，避免结果刷屏。
 */
@Composable
private fun OutputBlock(
    lines: List<String>,
    success: Boolean?,
) {
    val color = if (success == false) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    SelectionContainer {
        Text(
            text = lines.joinToString("\n"),
            style = MonoStyle,
            color = color,
        )
    }
}

@Composable
private fun StatusIcon(entry: AdbLogEntry) {
    when {
        entry.running -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        entry.success == true -> Icon(
            imageVector = Icons.Outlined.TaskAlt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        else -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("adb", text))
}
