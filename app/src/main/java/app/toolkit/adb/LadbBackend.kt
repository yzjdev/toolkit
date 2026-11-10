package app.toolkit.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 基于内置 AOSP adb 二进制的后端（方案参考 LADB）。
 *
 * 二进制放在 `jniLibs/libadb.so`：Android 会把它解压到 `nativeLibraryDir`
 * 并赋予可执行权限，因此可以像普通程序一样以子进程方式调用。
 *
 * 优点：能力最全（push / pull / install / forward 等全是现成的），
 * 协议细节（TLS 配对、密钥、sync）由 adb 自己处理。
 * 代价：多一个常驻 adb server 进程，APK 体积增加。
 *
 * 每条命令都是独立的 `adb` 进程调用，因此能拿到准确的退出码；
 * 相应地，shell 状态（cd、export）不会跨命令保留。
 */
class LadbBackend(private val context: Context) : AdbBackend {

    override val engine = AdbEngine.LADB

    private val adbPath: String = "${context.applicationInfo.nativeLibraryDir}/libadb.so"

    /** 多设备时选中的序列号，用于给所有命令加 `-s`。 */
    @Volatile
    private var serial: String? = null

    @Volatile
    private var connected = false

    /** 当前正在执行的 adb 子进程，[abort] 靠它把进程打掉。 */
    @Volatile
    private var runningProcess: Process? = null

    /** 本次命令是否被 [abort] 主动中止，用于区分「中止」与「超时/失败」。 */
    @Volatile
    private var aborted = false

    override fun isConnected(): Boolean = connected

    override fun abort() {
        aborted = true
        // destroyForcibly 是幂等的；进程已退出时不会报错
        runCatching { runningProcess?.destroyForcibly() }
    }

    override suspend fun pair(host: String, port: Int, code: String): String = withContext(Dispatchers.IO) {
        ensureBinary()
        aborted = false
        val target = "$host:$port"
        val process = startProcess(listOf("pair", target))
        runningProcess = process
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = thread { runCatching { process.inputStream.bufferedReader().use { out.append(it.readText()) } } }
        val errThread = thread { runCatching { process.errorStream.bufferedReader().use { err.append(it.readText()) } } }

        try {
            // adb pair 会提示输入配对码，从 stdin 写入一行即可
            runCatching {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(code)
                    writer.newLine()
                    writer.flush()
                }
            }

            val finished = process.waitFor(PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            outThread.join(2_000)
            errThread.join(2_000)

            // 被中止时进程退出码不可信，按中止返回
            if (aborted) throw AdbAbortedException()

            val text = (out.toString() + err.toString()).trim()
            // 配对会改动 server 状态，清掉旧的连接记录
            runCatching { runCommand(listOf("kill-server"), 10_000) }
            serial = null
            connected = false

            val paired = finished && process.exitValue() == 0
            when {
                paired -> "配对成功 $target"
                text.contains("Successfully paired", ignoreCase = true) -> "配对成功 $target"
                else -> throw AdbException(
                    text.ifBlank { "配对失败：请确认配对码与端口，且无线调试配对窗口未关闭" },
                )
            }
        } finally {
            runningProcess = null
        }
    }

    override suspend fun connect(host: String, port: Int): String = withContext(Dispatchers.IO) {
        ensureBinary()
        // 先清掉可能残留的旧连接，避免连到上一次的端口
        runCatching { runCommand(listOf("disconnect", "$host:$port"), 10_000) }

        runCommand(listOf("start-server"), START_TIMEOUT_MS)

        val target = "$host:$port"
        val connectResult = runCommand(listOf("connect", target), CONNECT_TIMEOUT_MS)
        val connectText = connectResult.combined
        val connectFailed = connectText.contains("cannot connect", ignoreCase = true) ||
            connectText.contains("failed to connect", ignoreCase = true) ||
            connectText.contains("unable to connect", ignoreCase = true)
        if (connectFailed) {
            throw AdbException("连接失败：${connectText.ifBlank { "adb connect 返回 ${connectResult.exitCode}" }}")
        }

        // 等设备真正就绪，再从设备列表里挑本次连接目标的序列号
        runCatching { runCommand(listOf("wait-for-device"), CONNECT_TIMEOUT_MS) }
        serial = pickSerial(target)

        val probe = runCommand(listOf("shell", "echo adb-ok"), PROBE_TIMEOUT_MS)
        if (!probe.combined.contains("adb-ok")) {
            throw AdbException("握手失败：${probe.combined.ifBlank { "无输出" }}")
        }

        connected = true
        val suffix = serial?.let { "（$it）" }.orEmpty()
        "已连接 $target$suffix"
    }

    override fun disconnect() {
        connected = false
        serial = null
        // kill-server 会阻塞，放到后台线程，避免卡住调用方
        runCatching {
            thread(isDaemon = true) {
                runCatching { runCommand(listOf("kill-server"), 10_000) }
            }
        }
    }

    override suspend fun shell(command: String): AdbResult = withContext(Dispatchers.IO) {
        requireConnection()
        runCommand(listOf("shell", command), SHELL_TIMEOUT_MS)
    }

    override val supportsAdbCommands: Boolean get() = true

    /**
     * 执行原生 adb 子命令。
     *
     * 不做 `requireConnection()` 检查：`devices`、`version`、`start-server`
     * 这类命令本身就是用来查状态或建立连接的，未连接时也应该能跑。
     */
    override suspend fun adbCommand(args: List<String>): AdbResult = withContext(Dispatchers.IO) {
        ensureBinary()
        if (args.isEmpty()) throw AdbException("adb 子命令为空")
        // server 级命令作用于 adb 自身而非某台设备，带上 -s 会直接报错
        val applySerial = args[0].lowercase() !in SERVER_LEVEL_COMMANDS
        runCommand(args, ADB_COMMAND_TIMEOUT_MS, applySerial)
    }

    override suspend fun push(local: File, remote: String): String = withContext(Dispatchers.IO) {
        requireConnection()
        if (!local.isFile) throw AdbException("本地文件不存在：${local.absolutePath}")
        val result = runCommand(listOf("push", local.absolutePath, remote), TRANSFER_TIMEOUT_MS)
        if (!result.success) throw AdbException(result.combined.ifBlank { "push 失败（退出码 ${result.exitCode}）" })
        "${local.name} → $remote（${local.length()} 字节）"
    }

    override suspend fun pull(remote: String, local: File): String = withContext(Dispatchers.IO) {
        requireConnection()
        local.parentFile?.mkdirs()
        val result = runCommand(listOf("pull", remote, local.absolutePath), TRANSFER_TIMEOUT_MS)
        if (!result.success) throw AdbException(result.combined.ifBlank { "pull 失败（退出码 ${result.exitCode}）" })
        "$remote → ${local.name}（${local.length()} 字节）"
    }

    override suspend fun install(apk: File, options: List<String>): String = withContext(Dispatchers.IO) {
        requireConnection()
        if (!apk.isFile) throw AdbException("APK 不存在：${apk.absolutePath}")
        val args = buildList {
            add("install")
            addAll(options.ifEmpty { listOf("-r") })
            add(apk.absolutePath)
        }
        val result = runCommand(args, INSTALL_TIMEOUT_MS)
        if (!result.success || !result.combined.contains("Success", ignoreCase = true)) {
            throw AdbException(result.combined.ifBlank { "安装失败（退出码 ${result.exitCode}）" })
        }
        "已安装 ${apk.name}"
    }

    override suspend fun uninstall(packageName: String): String = withContext(Dispatchers.IO) {
        requireConnection()
        val result = runCommand(listOf("uninstall", packageName), INSTALL_TIMEOUT_MS)
        if (!result.success || !result.combined.contains("Success", ignoreCase = true)) {
            throw AdbException(result.combined.ifBlank { "卸载失败（退出码 ${result.exitCode}）" })
        }
        "已卸载 $packageName"
    }

    /**
     * 从 `adb devices` 里挑出本次连接目标的序列号。
     *
     * 只接受与 [target] 匹配的条目。原先的兜底 `firstOrNull()` 会在目标未匹配时
     * 随便挑一台（比如本机的 emulator-5554），导致命令发到错误的设备上，
     * 所以这里宁可返回 null 交给上层判断，也不猜。
     */
    private fun pickSerial(target: String): String? {
        val result = runCatching { runCommand(listOf("devices"), 15_000) }.getOrNull() ?: return null
        val devices = result.stdout.lineSequence()
            .drop(1) // "List of devices attached"
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                // 只认状态为 device 的条目，offline / unauthorized 都不可用
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }
            .toList()

        // 目标地址可能是 `IP:端口`，也可能是 `主机名:端口`，两种都接受
        return devices.firstOrNull { it.equals(target, ignoreCase = true) }
            ?: devices.firstOrNull { it.startsWith("$target", ignoreCase = true) }
            ?: devices.firstOrNull { it.endsWith(":$target", ignoreCase = true) }
    }

    private fun ensureBinary() {
        if (!File(adbPath).canExecute()) {
            throw AdbException(
                "内置 adb 不可执行：$adbPath\n" +
                    "请确认已把 libadb.so 放入 jniLibs 且当前 ABI 已打包",
            )
        }
    }

    private fun requireConnection() {
        ensureBinary()
        if (!connected) throw AdbException("未连接，请先连接 adbd")
    }

    /**
     * 执行一次 adb 命令并收集 stdout / stderr / 退出码。
     *
     * 两个输出流用独立线程并发读取，避免管道写满后互相阻塞。
     * 执行期间把进程登记到 [runningProcess]，供 [abort] 中止。
     */
    private fun runCommand(
        args: List<String>,
        timeoutMs: Long,
        applySerial: Boolean = true,
    ): AdbResult {
        aborted = false
        val process = startProcess(args, applySerial)
        runningProcess = process
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = thread { runCatching { process.inputStream.bufferedReader().use { out.append(it.readText()) } } }
        val errThread = thread { runCatching { process.errorStream.bufferedReader().use { err.append(it.readText()) } } }

        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                // 可能是超时，也可能是被 abort：以 aborted 标志为准
                throw if (aborted) {
                    AdbAbortedException()
                } else {
                    AdbException("adb 命令超时（${timeoutMs}ms）：${args.joinToString(" ")}")
                }
            }
            outThread.join(2_000)
            errThread.join(2_000)

            // 进程被 abort 杀掉时退出码不可信，统一按中止处理
            if (aborted) throw AdbAbortedException()

            return AdbResult(process.exitValue(), out.toString(), err.toString())
        } finally {
            runningProcess = null
        }
    }

    private fun startProcess(args: List<String>, applySerial: Boolean = true): Process {
        val command = buildList {
            add(adbPath)
            // server 级命令（devices / version / start-server 等）作用于 adb 自身，
            // 带上 -s 会被 adb 拒绝，因此按调用方要求决定是否注入
            if (applySerial) {
                serial?.let {
                    add("-s")
                    add(it)
                }
            }
            addAll(args)
        }
        return try {
            ProcessBuilder(command)
                .directory(context.filesDir)
                .apply {
                    environment().apply {
                        // adb 把主机密钥放在 $HOME/.android/adbkey
                        put("HOME", context.filesDir.path)
                        put("TMPDIR", context.cacheDir.path)
                        // 关掉模拟器端口扫描（实测这是本机被自动接入的真正原因）。
                        // adb server 启动时会扫描 5554-5585 找模拟器，其中 console 端口
                        // 5554 对应 adb 端口 5555。本应用就跑在手机上，本机 adbd 正好
                        // 监听 5555，于是被当成 emulator-5554 自动接入，触发本机
                        // 「允许 USB 调试」弹窗。置 0 后扫描范围为空，设备列表只剩
                        // 显式 connect 的目标。
                        put("ADB_LOCAL_TRANSPORT_MAX_PORT", "0")
                        // 顺带关掉 mDNS 自动发现：本机的无线调试服务同样会被自动连上
                        put("ADB_MDNS", "0")
                        put("ADB_MDNS_AUTO_CONNECT", "")
                    }
                }
                .start()
        } catch (t: Throwable) {
            throw AdbException("无法启动 adb：${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    companion object {
        private const val START_TIMEOUT_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val PAIR_TIMEOUT_MS = 30_000L
        private const val SHELL_TIMEOUT_MS = 60_000L
        private const val TRANSFER_TIMEOUT_MS = 10 * 60_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60_000L

        /** 任意 adb 子命令的超时，比 shell 宽松一点，兼容 logcat 导出等 */
        private const val ADB_COMMAND_TIMEOUT_MS = 120_000L

        /**
         * 作用于 adb server 自身、而非某台已连接设备的子命令。
         *
         * 这类命令不能带 `-s`（会报 "more than one device" 或直接失败），
         * 而且未连接设备时也应该能执行。
         */
        private val SERVER_LEVEL_COMMANDS = setOf(
            "devices",
            "version",
            "start-server",
            "kill-server",
            "connect",
            "disconnect",
            "pair",
            "tcpip",
            "get-state",
            "get-serialno",
            "wait-for-device",
            "sideload",
            "bugreport",
            "help",
        )
    }
}
