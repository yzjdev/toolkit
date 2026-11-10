package app.toolkit.netguard

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.toolkit.R
import app.toolkit.priv.PrivilegeShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NetGuardUiState(
    val apps: List<InstalledApp> = emptyList(),
    val blocked: Set<String> = emptySet(),
    val query: String = "",
    val loading: Boolean = true,
    /** 正在下发规则的包名；只禁用这些行，避免整列表的开关一起变灰 */
    val applyingPackages: Set<String> = emptySet(),
    val privilegedReady: Boolean = false,
    val rulesActive: Boolean = false,
    val showSystemApps: Boolean = true,
    val showUserApps: Boolean = true,
) {
    val visibleApps: List<InstalledApp>
        get() {
            val q = query.trim()
            val filtered = apps.filter { app ->
                (if (app.isSystem) showSystemApps else showUserApps) &&
                    (q.isEmpty() ||
                        app.label.contains(q, ignoreCase = true) ||
                        app.packageName.contains(q, ignoreCase = true))
            }
            // 固定排序：已禁网在前，同组内按名称升序（不区分大小写）。
            // 用 compareTo(ignoreCase) 代替 lowercase()：排序期间比较器被调用 O(n log n) 次，
            // 每次 lowercase() 都要新建一个 String。包名作为 tie-breaker 保证同名应用顺序确定。
            val labelAsc = Comparator<InstalledApp> { a, b ->
                a.label.compareTo(b.label, ignoreCase = true)
            }
            val comparator: Comparator<InstalledApp> =
                compareBy<InstalledApp> { it.packageName !in blocked }
                    .then(labelAsc)
                    .thenBy { it.packageName }
            return filtered.sortedWith(comparator)
        }
}

class NetGuardViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(NetGuardUiState(blocked = NetworkGuard.loadBlocked(application)))
    val state: StateFlow<NetGuardUiState> = _state.asStateFlow()

    /** 串行化规则下发：NetworkGuard.apply 内部是读改写，并发执行会互相覆盖 */
    private val applyMutex = Mutex()

    init {
        refresh()
        observePrivilegeConnection()
    }

    /**
     * 特权服务是异步启动的：连接建立或断开后刷新列表状态，
     * 使「已禁网」数量、规则生效提示和行内开关可用性跟随真实连接。
     */
    private fun observePrivilegeConnection() {
        viewModelScope.launch {
            // 基线取当前连接状态，而不是 null：若服务在 init 的刷新之后、本收集器启动之前
            // 就已连上，首次发射就是 true，用 null 作基线会把这次变化丢掉，列表永远停在未连接。
            var previous = PrivilegeShell.isConnected()
            PrivilegeShell.connected.collect { connected ->
                if (connected == previous) return@collect
                previous = connected
                if (connected) {
                    NetGuardLog.info("特权服务已连接，刷新应用列表状态")
                    // 已有列表在显示，刷新时不要整屏转圈
                    refresh(showLoading = false)
                } else {
                    NetGuardLog.warn("特权服务已断开")
                    _state.update {
                        it.copy(privilegedReady = false, rulesActive = false)
                    }
                }
            }
        }
    }

    /**
     * 重新加载列表与规则状态。
     *
     * [showLoading] 为 true 时显示全屏进度（首次进入、手动刷新）；
     * 连接状态变化触发的后台刷新传 false，避免把已渲染的列表整屏顶掉。
     */
    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (showLoading) _state.update { it.copy(loading = true) }
            NetGuardLog.info("加载应用列表…")

            try {
                // reapply / syncBlocked 与 applyTo 都是持久化记录的读改写，
                // 必须共用同一把锁，否则连接触发的刷新会和用户操作互相覆盖。
                applyMutex.withLock {
                    val apps = AppListRepository.load(app)
                    val installed = apps.mapTo(HashSet()) { it.packageName }
                    // 1) 清掉已卸载应用的残留记录
                    val persisted = NetworkGuard.pruneUninstalled(app, installed)

                    val ready = PrivilegeShell.isConnected()
                    NetGuardLog.info(
                        "已加载 ${apps.size} 个应用，特权服务${if (ready) "已连接" else "未连接"}",
                    )

                    // 系统侧回读结果：包名 -> 是否已禁网；无法判定的包名不出现
                    var actual: Map<String, Boolean> = emptyMap()

                    if (ready) {
                        // 2) 幂等重下发：修复重启丢失、或被其它工具改掉的规则
                        if (persisted.isNotEmpty()) {
                            val reapplied = NetworkGuard.reapply(app)
                            if (reapplied.size < persisted.size) {
                                NetGuardLog.warn(
                                    "重下发未全部成功：${reapplied.size}/${persisted.size}，规则可能未完全生效",
                                )
                            }
                        }
                        // 3) 以系统侧真实状态为准校正本地记录
                        actual = NetworkGuard.readback(app, persisted)
                        if (actual.isNotEmpty()) {
                            val actualBlocked = actual.filterValues { it }.keys
                            if (actualBlocked != persisted.intersect(actual.keys)) {
                                NetGuardLog.warn("系统侧状态与本地记录不一致，已按系统侧校正")
                            }
                            NetworkGuard.syncBlocked(app, actual)
                        }
                    }

                    val blocked = NetworkGuard.loadBlocked(app)
                    val rulesActive = resolveRulesActive(app, ready, blocked, actual)
                    if (blocked.isNotEmpty() && !rulesActive && ready) {
                        NetGuardLog.warn("本地有 ${blocked.size} 条规则，但未确认全部生效")
                    }

                    _state.update {
                        it.copy(
                            apps = apps,
                            blocked = blocked,
                            loading = false,
                            privilegedReady = ready,
                            rulesActive = rulesActive,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 不兜住异常会让 loading 永久为 true，界面卡在转圈
                NetGuardLog.error("加载应用列表失败：${e.message ?: e.toString()}")
                _state.update {
                    it.copy(
                        loading = false,
                        privilegedReady = PrivilegeShell.isConnected(),
                        rulesActive = false,
                    )
                }
            }
        }
    }

    /**
     * 规则是否生效：以系统侧回读为准，记录中每个包名都被确认已禁网才算生效。
     * 只凭 BOOT_COUNT 会在部分下发失败时误报生效；回读不可用时才退回 BOOT_COUNT 判断。
     */
    private fun resolveRulesActive(
        app: Application,
        ready: Boolean,
        blocked: Set<String>,
        actual: Map<String, Boolean>,
    ): Boolean = when {
        !ready -> false
        blocked.isEmpty() -> true
        actual.isNotEmpty() -> blocked.all { actual[it] == true }
        else -> NetworkGuard.hasAppliedThisBoot(app)
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    /** 系统应用开关：若会导致两类都不显示，则忽略本次操作 */
    fun setShowSystemApps(visible: Boolean) = _state.update {
        if (!visible && !it.showUserApps) it else it.copy(showSystemApps = visible)
    }

    /** 用户应用开关：若会导致两类都不显示，则忽略本次操作 */
    fun setShowUserApps(visible: Boolean) = _state.update {
        if (!visible && !it.showSystemApps) it else it.copy(showUserApps = visible)
    }

    /** 单应用切换禁网 */
    fun toggle(packageName: String, block: Boolean) {
        NetGuardLog.info("用户操作：${if (block) "禁网" else "恢复"} $packageName")
        // 乐观更新：先翻转开关让界面立即响应，真实结果回来后再以持久化记录为准校正
        _state.update {
            it.copy(blocked = if (block) it.blocked + packageName else it.blocked - packageName)
        }
        applyTo(listOf(packageName), block)
    }

    private fun applyTo(packages: List<String>, block: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (!PrivilegeShell.isConnected()) {
                NetGuardLog.error("特权服务未连接，操作取消")
                Toast.makeText(app, R.string.netguard_not_ready, Toast.LENGTH_SHORT).show()
                // 撤销 toggle 里的乐观更新，回到持久化的真实状态
                _state.update { it.copy(blocked = NetworkGuard.loadBlocked(app)) }
                return@launch
            }
            // 只禁用本次操作的行后，其它行仍可点击，因此可能并发进入。
            // NetworkGuard.apply 内部是 loadBlocked→改→saveBlocked 的读改写，必须串行化，
            // 否则两次并发下发会互相覆盖 blocked 记录。
            // 标记放在锁外：先给用户即时反馈，不必等前一个操作完成。
            val targets = packages.toSet()
            _state.update { it.copy(applyingPackages = it.applyingPackages + targets) }
            try {
                applyMutex.withLock {
                    val results = NetworkGuard.apply(app, packages, block)
                    val failed = results.filterValues { !it }.keys

                    if (failed.isNotEmpty()) {
                        // 失败用 Toast 提示应用名，避免在 Snackbar 里堆包名
                        val labels = _state.value.apps.associate { it.packageName to it.label }
                        failed.take(MAX_FAILURE_TOASTS).forEach { pkg ->
                            val label = labels[pkg] ?: pkg
                            Toast.makeText(
                                app,
                                app.getString(
                                    if (block) R.string.toast_block_failed else R.string.toast_restore_failed,
                                    label,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        if (failed.size > MAX_FAILURE_TOASTS) {
                            NetGuardLog.warn("另有 ${failed.size - MAX_FAILURE_TOASTS} 个应用失败，详见日志")
                        }
                    }

                    if (failed.isEmpty()) {
                        Toast.makeText(
                            app,
                            app.getString(
                                if (block) R.string.toast_blocked_count else R.string.toast_restored_count,
                                results.size,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    // 生效判定：本次有失败就无法确认全部生效；没有规则视为生效；
                    // 其余包名沿用上次确认结果（本次只下发了 targets，未回读全量）。
                    val blockedNow = NetworkGuard.loadBlocked(app)
                    val rulesActiveNow = when {
                        failed.isNotEmpty() -> false
                        blockedNow.isEmpty() -> true
                        else -> _state.value.rulesActive
                    }
                    _state.update {
                        it.copy(
                            blocked = blockedNow,
                            rulesActive = rulesActiveNow,
                        )
                    }
                }
            } finally {
                // 取消或异常时也要复位，否则该行开关会永久卡在禁用态
                _state.update { it.copy(applyingPackages = it.applyingPackages - targets) }
            }
        }
    }

    private companion object {
        /** 失败 Toast 最多提示几个，其余只在日志里记录 */
        const val MAX_FAILURE_TOASTS = 3
    }
}
