package app.toolkit.lanshare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LanShareUiState(
    val running: Boolean = false,
    val port: Int = LanShareServer.DEFAULT_PORT,
    val url: String? = null,
    val rootLabel: String = "",
    /** 是否已拿到「所有文件访问权限」 */
    val hasPermission: Boolean = false,
    val error: String? = null,
    val starting: Boolean = false,
    /** 授权码设置：开关 + 码本身 */
    val accessCode: AccessCode = AccessCode(),
)

class LanShareViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LanShareUiState())
    val state: StateFlow<LanShareUiState> = _state.asStateFlow()

    val logs: StateFlow<List<LanShareLogEntry>> get() = LanShareLog.entries

    init {
        refreshPermission()
        // 进程被杀后服务不会残留，但 ViewModel 重建时可能仍在运行，这里同步一次真实状态
        syncFromServer()
        // 授权码是持久化设置，必须在服务启动前就交给服务端，
        // 否则重启后服务会有一小段时间是不设防的
        val code = LanShareAccessCode.load(getApplication<Application>())
        LanShareServer.setAccessCode(code.effectiveCode())
        _state.update { it.copy(accessCode = code) }
    }

    /** 打开/关闭授权码。关闭时服务端立即放行，已连接的人不受影响。 */
    fun setAccessCodeEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        val current = _state.value.accessCode
        // 第一次打开开关时自动生成一个，省掉「打开了但没码」的尴尬状态
        val code = if (enabled && current.code.isEmpty()) {
            current.copy(enabled = true, code = LanShareAccessCode.random())
        } else {
            current.copy(enabled = enabled)
        }
        applyAccessCode(context, code)
    }

    /** 用户手动输入的授权码：只保留数字，最多 4 位。 */
    fun setAccessCodeValue(value: String) {
        val context = getApplication<Application>()
        val sanitized = LanShareAccessCode.sanitize(value)
        applyAccessCode(context, _state.value.accessCode.copy(code = sanitized))
    }

    /** 重新随机一个授权码。 */
    fun randomizeAccessCode() {
        val context = getApplication<Application>()
        val code = _state.value.accessCode.copy(
            enabled = true,
            code = LanShareAccessCode.random(),
        )
        applyAccessCode(context, code)
    }

    private fun applyAccessCode(context: Application, code: AccessCode) {
        LanShareAccessCode.save(context, code)
        // 服务已经在跑时改码只更新校验值，不重启服务：
        // 重启会掐断正在传输的连接
        LanShareServer.setAccessCode(code.effectiveCode())
        _state.update { it.copy(accessCode = code) }
    }

    /** 从系统设置页回来时调用：权限可能刚被授予。 */
    fun refreshPermission() {
        val context = getApplication<Application>()
        val granted = ExternalStorage.isGranted(context)
        _state.update {
            it.copy(
                hasPermission = granted,
                rootLabel = if (granted) ExternalStorage.rootDir().absolutePath else it.rootLabel,
            )
        }
        // 权限被撤销时服务无法继续读写，直接停掉，避免浏览器端一直报错
        if (!granted && LanShareServer.isRunning()) stop()
    }

    fun start() {
        val context = getApplication<Application>()
        val current = _state.value
        if (current.starting || current.running) return
        if (!current.hasPermission) {
            _state.update { it.copy(error = "需要「所有文件访问权限」才能读写手机存储") }
            return
        }
        _state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { ExternalStorage.root(context) }
            if (root == null) {
                _state.update {
                    it.copy(starting = false, error = "权限已失效，请重新授予「所有文件访问权限」")
                }
                return@launch
            }
            val result = withContext(Dispatchers.IO) { LanShareServer.start(context, current.port, root) }
            // starting 归位与 running/url 必须在同一次 update 里完成：
            // 先单独发一次 starting=false 会闪出一帧「没在启动、也没在运行」的中间态，
            // 开关和地址区都会跟着抖一下。
            syncFromServer(starting = false)
            result.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "启动失败") }
            }
            if (result.isSuccess) {
                // 服务地址先出来，再提升前台；锁屏后进程仍可响应请求
                LanShareService.start(context, LanShareServer.state.value.url)
            }
        }
    }

    fun stop() {
        LanShareService.stop(getApplication())
        LanShareServer.stop()
        syncFromServer()
    }

    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    /** 回到页面时调用：网络可能已经变了，地址与前台通知都要跟着更新。 */
    fun refreshAddress() {
        if (!LanShareServer.isRunning()) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LanShareServer.refreshAddress() }
            val url = LanShareServer.state.value.url
            if (url != null) LanShareService.start(context, url)
            syncFromServer()
        }
    }

    fun clearLogs() = LanShareLog.clear()

    /**
     * 把服务端的真实状态同步到 UI 状态。
     *
     * [starting] 传 null 表示保持原值；启动结束时必须和 running/url 一起写进去，
     * 否则会出现一帧「没在启动、也没在运行」的空档，界面跟着闪。
     */
    private fun syncFromServer(starting: Boolean? = null) {
        val server = LanShareServer.state.value
        _state.update {
            it.copy(
                running = server.running,
                port = server.port,
                url = server.url,
                rootLabel = server.rootLabel.ifEmpty { it.rootLabel },
                error = if (server.running) null else it.error,
                starting = starting ?: it.starting,
            )
        }
    }
}
