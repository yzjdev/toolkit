package app.toolkit.lanshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 「所有文件访问权限」的判定与申请入口。
 *
 * Android 11+ 用 MANAGE_EXTERNAL_STORAGE，只能在系统设置页里授予；
 * Android 10 及以下退回传统的读写外部存储运行时权限。
 */
object ExternalStorage {

    /** 传输根目录：外部存储根，也就是文件管理器里的「内部存储」。 */
    @Suppress("DEPRECATION")
    fun rootDir(): File = Environment.getExternalStorageDirectory()

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            runtimePermissions().all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** 当前系统用运行时权限控制（而非系统设置页）。 */
    fun usesRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** Android 10 及以下需要的运行时权限；11+ 不走这里。 */
    fun runtimePermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /**
     * 跳系统设置页的候选 Intent，按优先级排列。
     *
     * 先试应用专属页面；部分 OEM 没有这个 Activity，再退回全局列表页。
     * Android 10 及以下返回空列表，表示走运行时权限。
     */
    fun settingsIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
    }

    /** 构造根目录；未授权时返回 null，服务不应该在无权限时启动。 */
    fun root(context: Context): TransferRoot? =
        if (isGranted(context)) FileTransferRoot(rootDir()) else null}
