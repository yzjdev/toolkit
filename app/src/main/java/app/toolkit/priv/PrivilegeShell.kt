package app.toolkit.priv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.command.PrivilegeCommand

/**
 * 特权命令执行结果。
 */
data class ShellOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0

    val errorText: String
        get() = stderr.trim().ifEmpty { stdout.trim() }.ifEmpty { "exit code $exitCode" }
}

/**
 * 通过 priv-kit 命令 API 执行 shell 命令（服务端以 shell/root 身份运行）。
 *
 * 连接状态统一观察进程级 [Privilege.serverState]，不自行 ping。
 */
object PrivilegeShell {

    /** 命令超时；null 表示不设截止时间（见 priv-kit commands 文档） */
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /**
     * 特权服务连接状态（进程级）。
     *
     * 每个新收集者会立即收到当前值，界面可在服务启动或断开时同步刷新。
     */
    val connected: Flow<Boolean> =
        Privilege.serverState.map { it != null }.distinctUntilChanged()

    fun isConnected(): Boolean = Privilege.serverState.value != null

    suspend fun exec(
        command: String,
        timeoutMillis: Long? = DEFAULT_TIMEOUT_MS,
    ): ShellOutcome = withContext(Dispatchers.IO) {
        try {
            // 需要 shell 解析时显式传入 /system/bin/sh -c
            val process = Privilege.startCommand(
                PrivilegeCommand(
                    arguments = listOf("/system/bin/sh", "-c", command),
                    environment = emptyMap(),
                    workingDirectory = "/data/local/tmp",
                ),
                timeoutMillis,
            )
            val result = process.use { it.awaitResult() }
            ShellOutcome(
                exitCode = result.exitCode,
                stdout = result.stdout.toString(Charsets.UTF_8),
                stderr = result.stderr.toString(Charsets.UTF_8),
            )
        } catch (t: Throwable) {
            ShellOutcome(exitCode = -1, stdout = "", stderr = t.message ?: t.toString())
        }
    }
}
