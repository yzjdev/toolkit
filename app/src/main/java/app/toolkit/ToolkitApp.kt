package app.toolkit

import android.app.Application
import android.content.Context
import android.os.Build
import app.toolkit.priv.privilegeUiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import priv.kit.core.PrivilegeConfig
import priv.kit.ui.PrivilegeUi

class ToolkitApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // priv-kit 需要 hidden API 访问（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 特权进程崩溃日志目录（可选）
        PrivilegeConfig.crashLogDirectory = getExternalFilesDir("privilege-crashes")
        // 应用无需后台运行：owner 进程退出后立即结束特权服务，
        // 不保留默认的 10 分钟等待窗口（默认值见 PrivilegeProtocol）。
        // 已下发的禁网规则落在 netd，服务退出不影响其生效。
        PrivilegeConfig.configure(
            followDeathDelayMillis = 0L,
            activeReconnectOnOwnerDeath = false,
        )
        appScope.launch(Dispatchers.IO) {
            // 手动启动脚本按配置准备一次；配置为 null 时使用完整原生命令
            PrivilegeUi.prepareExternalShellScript(privilegeUiConfig.manualShellScriptPath)
        }
        appScope.launch {
            // 静默启动：默认遵循自动恢复设置，按上次成功的方式恢复特权服务
            PrivilegeUi.startSilently(config = privilegeUiConfig)
        }
    }
}
