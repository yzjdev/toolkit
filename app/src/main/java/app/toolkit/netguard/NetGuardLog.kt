package app.toolkit.netguard

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class NetGuardLogEntry(
    val id: Long,
    val time: String,
    val level: LogLevel,
    val message: String,
)

/**
 * 禁网操作日志：内存环形缓冲（最近 200 条），同时写入 logcat。
 * 页面内可查看 / 清空，便于排查规则是否真正下发成功。
 */
object NetGuardLog {

    private const val TAG = "NetGuard"
    private const val MAX_ENTRIES = 200

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val counter = AtomicLong(0)

    private val _entries = MutableStateFlow<List<NetGuardLogEntry>>(emptyList())
    val entries: StateFlow<List<NetGuardLogEntry>> = _entries.asStateFlow()

    fun info(message: String) = append(LogLevel.INFO, message)

    fun success(message: String) = append(LogLevel.SUCCESS, message)

    fun warn(message: String) = append(LogLevel.WARN, message)

    fun error(message: String) = append(LogLevel.ERROR, message)

    fun clear() {
        _entries.value = emptyList()
    }

    /** 导出为纯文本，便于复制粘贴。 */
    fun dump(): String = entries.value.joinToString("\n") {
        "${it.time} ${it.level.name.padEnd(7)} ${it.message}"
    }

    private fun append(level: LogLevel, message: String) {
        when (level) {
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.SUCCESS -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
        val entry = NetGuardLogEntry(
            id = counter.incrementAndGet(),
            time = LocalTime.now().format(timeFormatter),
            level = level,
            message = message,
        )
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }
}
