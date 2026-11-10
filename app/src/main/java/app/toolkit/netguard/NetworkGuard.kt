package app.toolkit.netguard

import android.content.Context
import android.provider.Settings
import app.toolkit.priv.PrivilegeShell

/**
 * 禁网规则：调用 ConnectivityService 的 per-package networking 开关
 * （等价于 shell 的 `cmd connectivity set-package-networking-enabled`）。
 *
 * 规则生效在 netd/system_server 一侧，应用被杀后台也不会失效，但**重启后会丢失**，
 * 也可能被其它工具改动。因此本地记录只是「意图」，真实状态以 [readback] 为准。
 */
object NetworkGuard {

    private const val PREFS = "net_guard_prefs"
    private const val KEY_BLOCKED = "blocked_packages"
    private const val KEY_BOOT_COUNT = "applied_boot_count"

    private const val SET_CHAIN3_ENABLED = "cmd connectivity set-chain3-enabled true"
    private const val SET_CHAIN3_DISABLED = "cmd connectivity set-chain3-enabled false"
    private const val GET_PACKAGE_NETWORKING = "cmd connectivity get-package-networking-enabled"

    /** 回读时每批最多的包数，避免命令过长 */
    private const val MAX_READBACK_PACKAGES = 60

    fun loadBlocked(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED, emptySet())
            .orEmpty()
            .toSet()

    fun saveBlocked(context: Context, blocked: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BLOCKED, blocked)
            .apply()
    }

    /**
     * 用系统侧回读结果校正本地记录。
     *
     * [actual] 只包含可判定的包名：其中为 true 的加入记录、为 false 的移出记录。
     * 回读无法判定的包名不出现在 [actual] 里，保留原记录不做改动。
     */
    fun syncBlocked(context: Context, actual: Map<String, Boolean>) {
        if (actual.isEmpty()) return
        val blocked = loadBlocked(context).toMutableSet()
        var changed = false
        actual.forEach { (pkg, isBlocked) ->
            val modified = if (isBlocked) blocked.add(pkg) else blocked.remove(pkg)
            if (modified) changed = true
        }
        if (changed) saveBlocked(context, blocked)
    }

    /**
     * 清理已卸载应用的残留记录，返回清理后的集合。
     */
    fun pruneUninstalled(context: Context, installed: Set<String>): Set<String> {
        val blocked = loadBlocked(context)
        val kept = blocked.filterTo(LinkedHashSet()) { it in installed }
        if (kept.size != blocked.size) {
            NetGuardLog.warn("清理 ${blocked.size - kept.size} 个已卸载应用的禁网记录")
            saveBlocked(context, kept)
        }
        return kept
    }

    /**
     * 对一批包名下发禁网/放行，返回每个包名的执行结果。
     */
    suspend fun apply(
        context: Context,
        packages: Collection<String>,
        block: Boolean,
    ): Map<String, Boolean> {
        if (packages.isEmpty()) {
            NetGuardLog.warn("空目标列表，跳过${if (block) "禁网" else "恢复"}")
            return emptyMap()
        }

        val action = if (block) "禁网" else "恢复"
        NetGuardLog.info("开始$action ${packages.size} 个应用")

        val outcome = PrivilegeShell.exec(buildScript(packages, block))
        NetGuardLog.info("特权命令退出码=${outcome.exitCode}")
        if (!outcome.success) {
            NetGuardLog.error("特权命令失败：${outcome.errorText}")
        }

        val codes = parseExitCodes(outcome.stdout)
        if (codes.isEmpty() && !outcome.success) {
            // 脚本整体未执行，不改本地状态
            NetGuardLog.error("${action}未生效，保持原状态")
            return packages.associateWith { false }
        }
        val results = packages.associateWith { codes[it] == 0 }
        val unconfirmed = packages.filter { it !in codes }
        if (unconfirmed.isNotEmpty()) {
            NetGuardLog.warn("${unconfirmed.size} 个应用未返回退出码，按失败处理")
        }

        // 只把真正成功的包名写入持久化状态
        val blocked = loadBlocked(context).toMutableSet()
        var changed = false
        results.forEach { (pkg, ok) ->
            if (!ok) return@forEach
            val modified = if (block) blocked.add(pkg) else blocked.remove(pkg)
            if (modified) changed = true
        }

        if (changed) saveBlocked(context, blocked)

        val ok = results.filterValues { it }.keys
        val failed = results.filterValues { !it }.keys
        if (ok.isNotEmpty()) {
            markAppliedThisBoot(context)
            NetGuardLog.success("${action}成功 ${ok.size} 个：${ok.joinToString(", ").take(300)}")
        }
        failed.forEach { pkg ->
            NetGuardLog.error("${action}失败 $pkg")
        }

        // 全部恢复后再关闭链，避免残留规则
        if (!block && blocked.isEmpty()) {
            val reset = PrivilegeShell.exec(SET_CHAIN3_DISABLED)
            if (reset.success) {
                NetGuardLog.info("已关闭 OEM deny 链（当前无禁网应用）")
            } else {
                NetGuardLog.warn("关闭 OEM deny 链失败：${reset.errorText}")
            }
        }
        return results
    }

    /**
     * 按持久化状态重新下发（幂等），返回成功应用的包名集合。
     *
     * 目前唯一的调用点是进入页面时的自愈（见 NetGuardViewModel.refresh）。
     * 本应用不监听开机广播，因此设备重启后规则会一直失效，直到用户下次打开应用。
     */
    suspend fun reapply(context: Context): Set<String> {
        val blocked = loadBlocked(context)
        if (blocked.isEmpty()) {
            NetGuardLog.info("重新下发：无持久化规则，跳过")
            return emptySet()
        }
        NetGuardLog.info("重新下发 ${blocked.size} 条规则")
        val results = apply(context, blocked, block = true)
        return results.filterValues { it }.keys
    }

    /**
     * 从系统侧回读真实联网状态（best-effort）。
     *
     * 返回 包名 -> 是否已禁网；无法判定的包名不出现在结果里（例如系统不支持该查询命令）。
     */
    suspend fun readback(context: Context, packages: Collection<String>): Map<String, Boolean> {
        if (packages.isEmpty()) return emptyMap()
        val parsed = HashMap<String, Boolean>(packages.size)
        var partialFailure = false
        // 分批回读，确保覆盖全部记录；只回读前 N 个会让其余记录在同步时被误删
        packages.chunked(MAX_READBACK_PACKAGES).forEach { chunk ->
            val script = buildString {
                chunk.forEach { pkg ->
                    appendLine("echo \"<<$pkg>>\"")
                    appendLine("$GET_PACKAGE_NETWORKING $pkg")
                    appendLine("echo \"<</$pkg>>\"")
                }
            }
            val stdout = PrivilegeShell.exec(script).stdout
            if (stdout.isBlank()) {
                partialFailure = true
                return@forEach
            }
            chunk.forEach { pkg ->
                val state = parsePackageNetworking(stdout, pkg) ?: return@forEach
                parsed[pkg] = state
            }
        }
        when {
            parsed.isEmpty() -> NetGuardLog.warn("系统侧未返回可识别的状态，沿用本地记录")
            partialFailure -> NetGuardLog.warn("系统侧回读部分失败，已回读 ${parsed.size}/${packages.size} 个")
            else -> NetGuardLog.info("系统侧回读成功 ${parsed.size}/${packages.size} 个")
        }
        return parsed
    }

    /**
     * 解析单个包名的联网状态：`true` 表示已禁网。
     *
     * 命令不支持或输出异常时返回 null，表示无法判定。
     */
    private fun parsePackageNetworking(stdout: String, pkg: String): Boolean? {
        val begin = "<<$pkg>>"
        val end = "<</$pkg>>"
        val from = stdout.indexOf(begin)
        if (from < 0) return null
        val to = stdout.indexOf(end, from + begin.length)
        if (to < 0) return null
        val body = stdout.substring(from + begin.length, to).trim().lowercase()
        return when {
            // networking 关闭 => 已禁网
            body.contains("false") -> true
            body.contains("true") -> false
            else -> null
        }
    }

    /** 本次开机后是否成功下发过规则（重启后 netd 规则会丢失） */
    fun hasAppliedThisBoot(context: Context): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BOOT_COUNT, -1)
        val current = currentBootCount(context)
        return stored != -1 && current != -1 && stored == current
    }

    private fun markAppliedThisBoot(context: Context) {
        val current = currentBootCount(context)
        if (current == -1) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BOOT_COUNT, current)
            .apply()
    }

    private fun currentBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun buildScript(packages: Collection<String>, block: Boolean): String = buildString {
        // 与 ShizuWall 一致：禁网前先打开 OEM deny 链
        if (block) appendLine(SET_CHAIN3_ENABLED)
        val flag = if (block) "false" else "true"
        packages.forEach { pkg ->
            appendLine("cmd connectivity set-package-networking-enabled $flag $pkg; echo \"$pkg:\$?\"")
        }
    }

    /**
     * 解析脚本输出中的 `包名:退出码` 行。缺失的包名视为未确认。
     */
    internal fun parseExitCodes(stdout: String): Map<String, Int> {
        val pattern = Regex("^\\s*(\\S+):(\\d+)\\s*$")
        val codes = HashMap<String, Int>()
        stdout.lineSequence().forEach { line ->
            val match = pattern.find(line) ?: return@forEach
            val code = match.groupValues[2].toIntOrNull() ?: return@forEach
            codes[match.groupValues[1]] = code
        }
        return codes
    }
}
