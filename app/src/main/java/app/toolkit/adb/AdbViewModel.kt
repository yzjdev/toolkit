package app.toolkit.adb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 本机 adbd 的默认端口（service.adb.tcp.port） */
const val DEFAULT_ADB_PORT = 5555

/**
 * 一条 ADB 操作记录。操作按时间顺序追加，最近的在最下面。
 */
data class AdbLogEntry(
    val id: Long,
    val title: String,
    /** 成功时是结果描述，失败时是错误原因 */
    val detail: String = "",
    val running: Boolean = false,
    /** null 表示执行中或尚未有结果 */
    val success: Boolean? = null,
)

data class AdbUiState(
    val connected: Boolean = false,
    /** 已连接设备的 `IP:端口`，未连接时为 null */
    val connectedTarget: String? = null,
    /** 有操作正在执行：连接、安装这类长任务期间禁用输入 */
    val busy: Boolean = false,
    val command: String = "",
    val entries: List<AdbLogEntry> = emptyList(),
)

/**
 * ADB 页面的状态持有者。
 *
 * 只使用 [LadbBackend]（内置 AOSP adb 二进制）。
 *
 * 界面只有一个命令输入框，所有操作都由命令驱动：
 * `adb connect IP:端口`、`adb pair IP:端口 配对码`、`adb disconnect`、
 * `adb install /path/x.apk`、`adb push 本地 远端`、`adb pull 远端 [本地]`、
 * `adb uninstall 包名`，以及其它任意命令（原样交给 shell）。
 */
class AdbViewModel(application: Application) : AndroidViewModel(application) {

    private val backend: AdbBackend = LadbBackend(application)

    private val _state = MutableStateFlow(AdbUiState())
    val state: StateFlow<AdbUiState> = _state.asStateFlow()

    private val counter = AtomicLong(0)

    /** 当前正在执行的操作，[abort] 用它区分任务并等待收尾。 */
    @Volatile
    private var runningJob: Job? = null

    fun onCommandChange(value: String) = _state.update { it.copy(command = value) }

    /**
     * 中止正在执行的命令。
     *
     * 交给后端真正中断底层操作（销毁 adb 子进程 / 关闭 shell 流），
     * 再把协程取消掉。结果由 [run] 的收尾逻辑回填为「已中止」。
     */
    fun abort() {
        if (!_state.value.busy) return
        backend.abort()
        runningJob?.cancel()
    }

    /** 提交输入框中的命令。 */
    fun submit() {
        val raw = _state.value.command.trim()
        if (raw.isEmpty() || _state.value.busy) return

        // 提交后清空输入框，方便连续输入
        _state.update { it.copy(command = "") }

        // 允许带或不带 adb 前缀：adb connect / connect 等价
        val tokens = raw.split(WHITESPACE).filter { it.isNotBlank() }
        val body = if (tokens.firstOrNull()?.equals("adb", ignoreCase = true) == true) {
            tokens.drop(1)
        } else {
            tokens
        }
        if (body.isEmpty()) {
            append(raw, "命令为空", success = false)
            return
        }

        when (body[0].lowercase()) {
            "connect" -> connect(body.getOrNull(1), raw)
            "pair" -> pair(body.getOrNull(1), body.getOrNull(2), raw)
            "disconnect" -> disconnect()
            "install" -> install(body.getOrNull(1), raw)
            "push" -> push(body.getOrNull(1), body.getOrNull(2), raw)
            "pull" -> pull(body.getOrNull(1), body.getOrNull(2), raw)
            "uninstall" -> uninstall(body.getOrNull(1), raw)
            "shell" -> shell(body.drop(1).joinToString(" ").ifBlank { raw }, raw)
            else -> {
                // 其余命令分两类，必须区分开：
                // - adb 自身子命令（devices / version / logcat …）交给 adb 程序；
                // - 设备端命令（pm / am / input / dumpsys …）交给设备 shell。
                // 之前一律走设备 shell，导致 `devices` 报 "sh: devices: not found"。
                if (body[0].lowercase() in adbLevelCommands) {
                    adbCommand(body, raw)
                } else {
                    shell(raw, raw)
                }
            }
        }
    }

    fun clear() {
        if (_state.value.busy) return
        _state.update { it.copy(entries = emptyList()) }
    }

    override fun onCleared() {
        backend.disconnect()
        super.onCleared()
    }

    private fun connect(target: String?, raw: String) {
        val (host, port) = parseHostPort(target, DEFAULT_ADB_PORT)
        if (host == null || port == null) {
            append(raw, "用法：adb connect IP:端口，例如 adb connect 127.0.0.1:5555", success = false)
            return
        }
        run("连接 $host:$port", target = "$host:$port") { backend.connect(host, port) }
    }

    private fun pair(target: String?, code: String?, raw: String) {
        val (host, port) = parseHostPort(target, null)
        if (host == null || port == null || code.isNullOrBlank()) {
            append(raw, "用法：adb pair IP:配对端口 配对码，例如 adb pair 127.0.0.1:37123 123456", success = false)
            return
        }
        run("配对 $host:$port") { backend.pair(host, port, code) }
    }

    /** 断开当前连接，界面菜单可直接调用。 */
    fun disconnect() {
        backend.disconnect()
        _state.update { it.copy(connected = false, connectedTarget = null) }
        append("断开连接", "已断开", success = true)
    }

    private fun install(path: String?, raw: String) {
        if (path.isNullOrBlank()) {
            append(raw, "用法：adb install /sdcard/Download/x.apk", success = false)
            return
        }
        val apk = File(path)
        run("安装 ${apk.name}") { backend.install(apk, listOf("-r")) }
    }

    private fun push(local: String?, remote: String?, raw: String) {
        if (local.isNullOrBlank() || remote.isNullOrBlank()) {
            append(raw, "用法：adb push 本地路径 设备路径", success = false)
            return
        }
        run("推送 ${File(local).name}") { backend.push(File(local), remote) }
    }

    private fun pull(remote: String?, local: String?, raw: String) {
        if (remote.isNullOrBlank()) {
            append(raw, "用法：adb pull 设备路径 [本地路径]", success = false)
            return
        }
        run("拉取 $remote") {
            val target = if (local.isNullOrBlank()) {
                // 未指定本地路径时落到应用外部目录，用户可用文件管理器直接取走
                val dir = getApplication<Application>().getExternalFilesDir("pull")
                    ?: getApplication<Application>().cacheDir
                dir.mkdirs()
                File(dir, remote.substringAfterLast('/').ifBlank { "pulled" })
            } else {
                File(local)
            }
            backend.pull(remote, target)
        }
    }

    private fun uninstall(pkg: String?, raw: String) {
        if (pkg.isNullOrBlank()) {
            append(raw, "用法：adb uninstall 包名", success = false)
            return
        }
        run("卸载 $pkg") { backend.uninstall(pkg) }
    }

    private fun shell(command: String, raw: String) {
        run(raw) {
            val result = backend.shell(command)
            val text = result.combined.ifBlank {
                if (result.exitCode == 0) "（无输出）" else "exit code ${result.exitCode}"
            }
            if (!result.success) throw AdbException(text)
            text
        }
    }

    /**
     * 执行 adb 自身的子命令（`devices`、`version`、`logcat` 等）。
     *
     * 与 [shell] 的区别是命令发给 adb 程序而不是设备 shell。
     * 后端不支持这类命令时直接给出提示，不发起执行。
     */
    private fun adbCommand(args: List<String>, raw: String) {
        if (!backend.supportsAdbCommands) {
            append(raw, "当前后端不支持 ${args[0]} 子命令", success = false)
            return
        }
        run(raw) {
            val result = backend.adbCommand(args)
            val text = result.combined.ifBlank {
                if (result.exitCode == 0) "（无输出）" else "exit code ${result.exitCode}"
            }
            if (!result.success) throw AdbException(text)
            text
        }
    }

    /**
     * 统一的执行包装：登记一条记录，执行成功/失败后回填结果。
     *
     * [block] 返回可直接展示的文本；抛 [Throwable] 视为失败。
     * [target] 非空表示这是一次连接操作，成功后记为当前连接目标，失败则清空。
     *
     * 被 [abort] 中止时按「已中止」回填，不算失败，也不改动连接状态。
     */
    private fun run(title: String, target: String? = null, block: suspend () -> String) {
        if (_state.value.busy) return
        val entryId = appendRunning(title)
        _state.update { it.copy(busy = true) }
        runningJob = viewModelScope.launch {
            try {
                val message = block()
                complete(entryId, message, true)
                if (target != null) {
                    _state.update { it.copy(connected = true, connectedTarget = target) }
                }
            } catch (c: CancellationException) {
                // 主动中止：保持连接状态不变，只把这条记录标成已中止
                complete(entryId, "已中止", false)
                throw c
            } catch (a: AdbAbortedException) {
                complete(entryId, "已中止", false)
            } catch (t: Throwable) {
                complete(entryId, errorText(t), false)
                if (target != null) {
                    _state.update { it.copy(connected = false, connectedTarget = null) }
                }
            } finally {
                runningJob = null
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * 解析 `host:port`。
     *
     * [defaultPort] 为 null 时要求显式端口；否则缺省用默认值。
     */
    private fun parseHostPort(target: String?, defaultPort: Int?): Pair<String?, Int?> {
        if (target.isNullOrBlank()) return null to null
        val parts = target.split(':')
        val host = parts[0].trim().ifBlank { null } ?: return null to null
        val port = if (parts.size >= 2) {
            parts[1].trim().toIntOrNull()?.takeIf { it in 1..65535 }
        } else {
            defaultPort
        }
        return if (port == null) null to null else host to port
    }

    private fun appendRunning(title: String): Long {
        val id = counter.incrementAndGet()
        _state.update {
            it.copy(entries = it.entries + AdbLogEntry(id = id, title = title, running = true))
        }
        return id
    }

    private fun append(title: String, detail: String, success: Boolean) {
        val id = counter.incrementAndGet()
        _state.update {
            it.copy(
                entries = it.entries + AdbLogEntry(
                    id = id,
                    title = title,
                    detail = detail,
                    running = false,
                    success = success,
                ),
            )
        }
    }

    private fun complete(id: Long, detail: String, success: Boolean) {
        _state.update { state ->
            state.copy(
                entries = state.entries.map { entry ->
                    if (entry.id != id) {
                        entry
                    } else {
                        entry.copy(running = false, detail = detail, success = success)
                    }
                },
            )
        }
    }

    private fun errorText(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * adb 自身的子命令名（`devices`、`version`、`logcat` 等）。
         *
         * 从命令树的顶层节点派生，避免分发逻辑里再维护一份重复清单；
         * `shell` 排除在外——它后面跟的是设备侧命令，另有分支处理。
         */
        private val adbLevelCommands: Set<String> by lazy {
            AdbCompletions.root.map { it.name.lowercase() }.toSet() - "shell"
        }
    }
}
