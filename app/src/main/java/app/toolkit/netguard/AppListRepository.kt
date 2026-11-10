package app.toolkit.netguard

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用条目。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val uid: Int,
)

/**
 * 读取已安装应用列表（不含自身）。
 */
object AppListRepository {

    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPkg = context.packageName
        // 列表只用 ApplicationInfo 的基础字段（标签/uid/flags/enabled），从不读取 metaData，
        // 因此 flags 传 0：省掉 PackageManager 为每个应用解析 <meta-data> Bundle 的开销。
        // 保留 getInstalledPackages 而非换成 getInstalledApplications，返回集合语义与原来完全一致。
        pm.getInstalledPackages(0)
            .asSequence()
            .filter { it.packageName != selfPkg }
            .mapNotNull { info ->
                val appInfo = info.applicationInfo ?: return@mapNotNull null
                if (!appInfo.enabled) return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    uid = appInfo.uid,
                )
            }
            .toList()
        // 排序统一由 UI 层按当前排序方式处理（见 NetGuardUiState.visibleApps），这里不预排序：
        // 省掉一次无用的 O(n log n) 与大量 lowercase() 临时字符串。
        // visibleApps 自带包名 tie-breaker，移除预排序不会让顺序变得不确定。
    }
}
