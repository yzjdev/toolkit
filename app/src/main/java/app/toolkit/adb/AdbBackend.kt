package app.toolkit.adb

import java.io.File

/** ADB 引擎。目前只有 LADB（内置 AOSP adb 二进制）一套实现。 */
enum class AdbEngine(val title: String, val detail: String) {
    LADB("LADB", "内置 AOSP adb 二进制，能力最全"),
}

/** 一次命令执行的结果。 */
data class AdbResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0

    /** stdout 与 stderr 合并后的展示文本。 */
    val combined: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty() && !endsWith("\n")) appendLine()
                append(stderr)
            }
        }.trimEnd()

    companion object {
        fun ok(stdout: String) = AdbResult(0, stdout, "")
        fun fail(message: String) = AdbResult(-1, "", message)
    }
}

/** ADB 操作失败。[message] 可直接展示给用户。 */
class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 命令被用户主动中止。与 [AdbException] 区分，界面按「已中止」展示而不是「失败」。 */
class AdbAbortedException : Exception("已中止")

/**
 * 统一的 ADB 后端接口。
 *
 * 当前实现只有 [LadbBackend]（驱动内置 adb 二进制）。
 * 保留接口是为了把「命令怎么执行」与界面/状态管理隔开，日后换实现不必动上层。
 *
 * 约定：
 * - 方法内部自行切到 IO 线程，调用方不必再包 withContext；
 * - 失败抛 [AdbException]，成功返回可直接展示的文本；
 * - [disconnect] 与 [abort] 必须可重复调用。
 */
interface AdbBackend {
    val engine: AdbEngine

    fun isConnected(): Boolean

    /**
     * 是否支持原生 adb 子命令（`devices`、`version`、`logcat` 等）。
     *
     * [LadbBackend] 驱动真正的 adb 二进制，支持全部子命令。
     */
    val supportsAdbCommands: Boolean get() = false

    /**
     * 执行一条原生 adb 子命令（参数里不含 `adb` 本身）。
     *
     * 与 [shell] 的区别：这里发给 **adb 程序**，作用于连接管理、日志、文件等；
     * 而 [shell] 是把命令发给 **设备上的 shell**。`devices` 只属于前者——
     * 发到设备上会得到 `sh: devices: not found`。
     */
    suspend fun adbCommand(args: List<String>): AdbResult =
        throw AdbException("当前引擎不支持 adb 子命令")

    /**
     * 中止正在执行的命令。
     *
     * 只终止当前这一条，不影响连接本身：销毁对应的 adb 子进程。
     * 没有正在执行的命令时调用它是安全的空操作。
     */
    fun abort()

    /** 无线调试配对（Android 11+）。配对与连接是两步，成功后仍需 [connect]。 */
    suspend fun pair(host: String, port: Int, code: String): String

    /** 连接 adbd。 */
    suspend fun connect(host: String, port: Int): String

    fun disconnect()

    suspend fun shell(command: String): AdbResult

    suspend fun push(local: File, remote: String): String

    suspend fun pull(remote: String, local: File): String

    suspend fun install(apk: File, options: List<String> = emptyList()): String

    suspend fun uninstall(packageName: String): String
}
