package app.toolkit.lanshare

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LanShareLevel { INFO, SUCCESS, WARN, ERROR }

data class LanShareLogEntry(
    val id: Long,
    val time: String,
    val level: LanShareLevel,
    val message: String,
)

/**
 * 传输日志：内存环形缓冲（最近 200 条），同时写 logcat。
 *
 * 服务端在后台线程处理请求，界面通过 [entries] 直接观察，
 * 不需要页面轮询。
 */
object LanShareLog {

    private const val TAG = "LanShare"
    private const val MAX_ENTRIES = 200

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val counter = AtomicLong(0)

    private val _entries = MutableStateFlow<List<LanShareLogEntry>>(emptyList())
    val entries: StateFlow<List<LanShareLogEntry>> = _entries.asStateFlow()

    fun info(message: String) = append(LanShareLevel.INFO, message)

    fun success(message: String) = append(LanShareLevel.SUCCESS, message)

    fun warn(message: String) = append(LanShareLevel.WARN, message)

    fun error(message: String) = append(LanShareLevel.ERROR, message)

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(level: LanShareLevel, message: String) {
        when (level) {
            LanShareLevel.INFO, LanShareLevel.SUCCESS -> Log.i(TAG, message)
            LanShareLevel.WARN -> Log.w(TAG, message)
            LanShareLevel.ERROR -> Log.e(TAG, message)
        }
        val entry = LanShareLogEntry(
            id = counter.incrementAndGet(),
            time = LocalTime.now().format(timeFormatter),
            level = level,
            message = message,
        )
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }
}
