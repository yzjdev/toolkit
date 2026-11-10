package app.toolkit.thermal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThermalUiState(
    /** 设备温度（摄氏度）；null 表示尚未读取或读取失败 */
    val celsius: Float? = null,
    val loading: Boolean = true,
) {
    val available: Boolean get() = celsius != null
}

class ThermalViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ThermalUiState())
    val state: StateFlow<ThermalUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { loadOnce() }
    }

    /** 页面在前台时轮询，温度变化慢，1 秒足够 */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                loadOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 页面离开前台时停止轮询 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch { loadOnce() }
    }

    /**
     * 读取一次设备温度。查询电池状态是阻塞调用，切到 IO 线程；
     * 由调用方 await，避免定时器比读取还快时协程堆积。
     */
    private suspend fun loadOnce() {
        val celsius = withContext(Dispatchers.IO) {
            ThermalRepository.load(getApplication())
        }
        _state.update { it.copy(celsius = celsius, loading = false) }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
