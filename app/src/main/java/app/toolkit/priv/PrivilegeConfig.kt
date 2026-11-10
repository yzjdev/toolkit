package app.toolkit.priv

import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiStartupMode

/**
 * 进程级 priv-ui 配置：前台 ViewModel 与静默启动共用同一实例。
 *
 * 对应官方 priv-ui 文档 "Share one configuration"：配置必须在进程作用域创建一次，
 * 同时传给前台 ViewModel 与无界面入口 [priv.kit.ui.PrivilegeUi.startSilently]。
 *
 * 启动方式与官方 sample / 默认值一致：Root、ADB（无线调试）、手动命令。
 */
val privilegeUiConfig: PrivilegeUiConfig by lazy {
    PrivilegeUiConfig(
        startupModes = listOf(
            PrivilegeUiStartupMode.ROOT,
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
        ),
        // 官方 "Fixed manual startup script"：使用外部脚本，显示更短的手动启动命令
        manualShellScriptPath = "priv.sh",
    )
}
