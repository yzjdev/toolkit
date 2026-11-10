package app.toolkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import app.toolkit.ui.adb.AdbScreen
import app.toolkit.ui.netguard.NetGuardScreen
import app.toolkit.ui.thermal.ThermalScreen
import app.toolkit.ui.theme.ToolkitTheme
import priv.kit.ui.PrivilegeScaffold

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToolkitTheme {
                ToolkitRoot()
            }
        }
    }
}

/** 底部导航：特权页不在此列，从禁网页顶栏图标进入 */
private enum class Dest(val labelRes: Int, val icon: ImageVector) {
    NETGUARD(R.string.tab_netguard, Icons.Outlined.WifiOff),
    THERMAL(R.string.tab_thermal, Icons.Outlined.Thermostat),
    ADB(R.string.tab_adb, Icons.Outlined.Adb),
}

@Composable
private fun ToolkitRoot() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var privilegeOpen by rememberSaveable { mutableStateOf(false) }

    if (privilegeOpen) {
        // 特权页覆盖整个界面；返回手势与顶栏返回都关闭它而不是退出应用。
        // 顶栏返回先走 viewModel.onBackClick()（返回 false），再落到 OnBackPressedDispatcher，
        // 因此这里注册的 BackHandler 能接管。
        BackHandler { privilegeOpen = false }
        Box(modifier = Modifier.fillMaxSize()) {
            PrivilegeScreen()
        }
        return
    }

    Scaffold(
        // 页面内部自带 Scaffold + TopAppBar，这里不再消费系统栏安全区，避免叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Dest.entries.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (Dest.entries[selected]) {
                Dest.NETGUARD -> NetGuardScreen(
                    onOpenPrivilege = { privilegeOpen = true },
                )
                Dest.THERMAL -> ThermalScreen()
                Dest.ADB -> AdbScreen()
            }
        }
    }
}

/**
 * priv-kit 授权页面：Root / ADB / 手动命令三种启动方式（与官方默认一致）。
 */
@Composable
private fun PrivilegeScreen() {
    PrivilegeScaffold(
        viewModel = viewModel<ToolkitPrivilegeViewModel>(),
    )
}
