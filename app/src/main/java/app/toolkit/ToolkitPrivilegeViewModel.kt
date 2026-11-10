package app.toolkit

import android.app.Application
import app.toolkit.priv.privilegeUiConfig
import priv.kit.ui.PrivilegeUiViewModel

/**
 * 复用进程级 [privilegeUiConfig] 的特权 UI 控制器。
 *
 * 构造参数只接收 Application，默认 ViewModel 工厂即可创建（见 priv-ui 接入说明
 * "Embed the scaffold"）。
 */
class ToolkitPrivilegeViewModel(application: Application) :
    PrivilegeUiViewModel(application, privilegeUiConfig) {

    override fun onBackClick(): Boolean = false
}
