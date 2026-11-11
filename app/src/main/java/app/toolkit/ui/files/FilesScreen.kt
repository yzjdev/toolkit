package app.toolkit.ui.files

import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.toolkit.R
import app.toolkit.priv.PrivilegeShell
import app.toolkit.ui.components.ToolkitTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 目录里的一个条目。[path] 是设备上的绝对路径。 */
private data class DeviceEntry(val path: String, val directory: Boolean, val size: Long)

/** 超过这个大小的文件不放进编辑器：内容要经命令行往返，过大既慢又可能超出参数长度上限。 */
private const val MAX_EDIT_BYTES = 512L * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pathInput by rememberSaveable { mutableStateOf("/sdcard") }
    // 已生效的目录：输入框每敲一个字都去查一次特权服务太浪费，只在确认时同步过来
    var path by rememberSaveable { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<DeviceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 编辑中的文件路径与内容：内容可能很大，不放进 savedInstanceState
    var editorPath by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val connected by PrivilegeShell.connected.collectAsStateWithLifecycle(initialValue = PrivilegeShell.isConnected())

    LaunchedEffect(path, refreshKey, connected) {
        if (!connected) {
            entries = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) { listDirectory(path) }
        result.onSuccess { entries = it }
            .onFailure { error = it.message ?: context.getString(R.string.files_list_failed) }
        loading = false
    }

    suspend fun openFile(file: DeviceEntry) {
        if (!isTextFile(file.path)) {
            Toast.makeText(context, R.string.files_not_text, Toast.LENGTH_SHORT).show()
            return
        }
        if (file.size > MAX_EDIT_BYTES) {
            Toast.makeText(context, R.string.files_too_large, Toast.LENGTH_LONG).show()
            return
        }
        val outcome = withContext(Dispatchers.IO) {
            PrivilegeShell.exec("base64 < ${shellQuote(file.path)}")
        }
        if (!outcome.success) {
            Toast.makeText(context, outcome.errorText, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            Base64.decode(outcome.stdout.filterNot(Char::isWhitespace), Base64.DEFAULT)
                .toString(Charsets.UTF_8)
        }.onSuccess {
            editorPath = file.path
            editorText = it
        }.onFailure {
            Toast.makeText(context, R.string.files_read_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun goParent() {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return
        val parent = File(trimmed).parent?.takeIf { it.isNotBlank() } ?: "/"
        path = parent
        pathInput = parent
    }

    fun applyPath() {
        val target = pathInput.trim().ifBlank { "/" }
        pathInput = target
        if (target == path) refreshKey++ else path = target
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ToolkitTopBar(
                title = stringResource(R.string.files_title),
                subtitle = path,
                actions = {
                    if (path.trimEnd('/').isNotEmpty()) {
                        TextButton(onClick = { goParent() }) {
                            Text(stringResource(R.string.files_parent))
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { applyPath() }),
                    label = { Text(stringResource(R.string.files_path)) },
                )
                Button(onClick = { applyPath() }, enabled = !loading && connected) {
                    Text(stringResource(R.string.files_open))
                }
            }

            when {
                !connected -> Text(
                    text = stringResource(R.string.files_privilege_required),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(24.dp),
                )
                error != null -> Text(
                    text = error.orEmpty(),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                entries.isEmpty() -> Text(
                    text = stringResource(R.string.files_empty),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.directory) {
                                        path = entry.path
                                        pathInput = entry.path
                                    } else {
                                        scope.launch { openFile(entry) }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (entry.directory) {
                                    Icons.Outlined.Folder
                                } else {
                                    Icons.Outlined.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (entry.directory) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp),
                            ) {
                                Text(File(entry.path).name.ifBlank { entry.path })
                                if (!entry.directory) {
                                    Text(
                                        text = formatSize(entry.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!entry.directory && isTextFile(entry.path)) {
                                TextButton(onClick = { scope.launch { openFile(entry) } }) {
                                    Text(stringResource(R.string.files_edit))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editorPath?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!saving) editorPath = null },
            title = { Text(File(target).name) },
            text = {
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14,
                    label = { Text(stringResource(R.string.files_content)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        val payload = Base64.encodeToString(
                            editorText.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP,
                        )
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                PrivilegeShell.exec(
                                    "printf '%s' '$payload' | base64 -d > ${shellQuote(target)}",
                                )
                            }
                            saving = false
                            if (outcome.success) {
                                editorPath = null
                                Toast.makeText(context, R.string.files_saved, Toast.LENGTH_SHORT).show()
                                refreshKey++
                            } else {
                                Toast.makeText(context, outcome.errorText, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator()
                    } else {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                    }
                    Text(stringResource(R.string.files_save), modifier = Modifier.padding(start = 8.dp))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!saving) editorPath = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 列出目录内容。
 *
 * 子目录与文件各走一次特权调用：路径用 NUL 分隔再整体 Base64，文件名里的空格、
 * 换行、引号都不会破坏解析；文件大小在同一次 find 的 -exec 里取，避免逐文件往返。
 */
private suspend fun listDirectory(path: String): Result<List<DeviceEntry>> {
    if (!path.startsWith('/')) {
        return Result.failure(IllegalArgumentException("请输入绝对路径，例如 /sdcard"))
    }
    val quoted = shellQuote(path)

    val directories = PrivilegeShell.exec(
        "find $quoted -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null | base64",
        60_000L,
    )
    if (!directories.success) return Result.failure(IllegalStateException(directories.errorText))

    // 文件列表带大小：在同一次 find 的 -exec 里 stat，避免逐文件一次特权往返。
    // 大小与路径都用 NUL 分隔再整体 Base64，文件名里的空格/换行/引号都不影响解析。
    val filesScript = buildString {
        append("find $quoted -mindepth 1 -maxdepth 1 -type f -exec sh -c ")
        append(
            shellQuote(
                // \000 是明确的八进制 NUL，比 \0 更稳妥（部分 printf 实现会把它当成空转义）
                "for f do printf '%s\\000%s\\000' \"\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)\" \"\$f\"; done",
            ),
        )
        append(" sh {} + 2>/dev/null | base64")
    }
    val files = PrivilegeShell.exec(filesScript, 60_000L)
    if (!files.success) return Result.failure(IllegalStateException(files.errorText))

    return runCatching {
        val dirs = decodeNul(directories.stdout)
            .map { DeviceEntry(it, directory = true, size = 0L) }
        val regularFiles = decodeNul(files.stdout)
            .chunked(2)
            .filter { it.size == 2 }
            .map { (size, filePath) -> DeviceEntry(filePath, directory = false, size = size.toLongOrNull() ?: 0L) }
        (dirs + regularFiles).sortedWith(
            compareBy<DeviceEntry> { !it.directory }
                .thenBy { File(it.path).name.lowercase() },
        )
    }
}

private fun decodeNul(block: String): List<String> {
    if (block.isBlank()) return emptyList()
    return runCatching {
        Base64.decode(block.filterNot(Char::isWhitespace), Base64.DEFAULT)
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotEmpty)
    }.getOrDefault(emptyList())
}

/** 用单引号包住参数，内部单引号按 shell 规则转义。 */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun isTextFile(path: String): Boolean = File(path).extension.lowercase() in setOf(
    "txt", "md", "json", "xml", "html", "htm", "css", "js", "kt", "java", "gradle",
    "properties", "yaml", "yml", "log", "csv", "sh", "conf", "ini",
)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}
