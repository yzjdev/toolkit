package app.toolkit.adb

/**
 * 补全树的一个节点。
 *
 * [children] 为空表示这是叶子命令，此时 [placeholders] 给出参数占位提示。
 */
data class AdbCommandSpec(
    val name: String,
    val description: String = "",
    val children: List<AdbCommandSpec> = emptyList(),
    /** 参数占位提示，例如 `<host:port>` */
    val placeholders: List<String> = emptyList(),
) {
    /**
     * 该命令是否需要继续输入才能执行。
     *
     * 三种情况返回 true：
     * 1. 存在以 `<` 开头的必填占位符（如 `<包名>`）；
     * 2. 参数是固定取值而非占位符（如 `input keyevent KEYCODE_HOME`），
     *    这类在 [keywordRequired] 里显式列出；
     * 3. 只有带子命令才有意义（如 `am`、`pm`）——裸跑只会打印整页帮助文本。
     *
     * 只有可选参数（`-r`、`[路径]`）或裸跑有意义的命令返回 false。
     */
    fun requiresArgument(): Boolean =
        placeholders.any { it.startsWith("<") } ||
            name in keywordRequired ||
            name in subcommandRequired
}

/**
 * 必须跟着子命令才有意义的命令。
 *
 * 这些命令裸跑不会执行任何操作，只会把 Activity Manager / Package Manager
 * 等子系统的帮助文本整页打印出来（`am` 一份就有近三百行）。
 * 注意 `logcat`、`reboot`、`shell`、`dumpsys` 虽有子命令，但裸跑本身有意义，
 * 不在此列。
 */
private val subcommandRequired = setOf(
    "am",
    "pm",
    "settings",
    "svc",
    "wm",
    "input",
)

/** 参数为固定取值、因此没有 `<...>` 占位符，但同样必须带参数的命令。 */
private val keywordRequired = setOf(
    "cat",      // cat <文件路径>：不带参数会读 stdin 而挂起
    "keyevent", // input keyevent <keycode>
    "content",  // content query|insert|delete|update
    "ip",       // ip addr|route
    "ime",      // ime list|set <id>
    "stayon",   // svc power stayon true|false
    "stack",    // am stack list
)

private fun leaf(name: String, description: String = "", vararg placeholders: String) =
    AdbCommandSpec(name, description, emptyList(), placeholders.toList())

private fun node(name: String, description: String, vararg children: AdbCommandSpec) =
    AdbCommandSpec(name, description, children.toList())

/**
 * 内置的 adb 命令树。
 *
 * 内容依据 https://github.com/mzlogin/awesome-adb 整理，覆盖文档中的全部顶层命令
 * 与 `shell` 下最常用的子命令。只收录「输入即用」的形式，说明保持简短。
 */
object AdbCompletions {

    /** 顶层命令（`adb ` 之后的部分）。 */
    val root: List<AdbCommandSpec> by lazy { rootSpecs() }

    private fun rootSpecs(): List<AdbCommandSpec> = listOf(
        // ---- 设备连接管理 ----
        leaf("devices", "列出已连接设备", "-l"),
        leaf("connect", "无线连接设备", "<IP:端口>"),
        leaf("disconnect", "断开无线连接", "[IP:端口]"),
        leaf("pair", "无线调试配对", "<IP:配对端口>", "<配对码>"),
        leaf("tcpip", "让设备在指定端口监听", "<端口>"),
        leaf("wait-for-device", "等待设备就绪"),
        leaf("get-state", "查看设备状态"),
        leaf("get-serialno", "查看设备序列号"),

        // ---- 启动 / 停止 ----
        leaf("start-server", "启动 adb server"),
        leaf("kill-server", "结束 adb server"),
        leaf("version", "查看 adb 版本"),

        // ---- 应用管理 ----
        // 参数依据 awesome-adb 的 install 章节：-l/-r/-t/-s/-d/-g，
        // 以及 --abi 与 --bypass-low-target-sdk-block
        leaf(
            "install",
            "安装 APK",
            "-r", "-d", "-g", "-t", "-l", "-s",
            "--abi", "--bypass-low-target-sdk-block",
            "<APK 路径>",
        ),
        leaf("uninstall", "卸载应用", "-k", "<包名>"),
        leaf("exec-out", "执行命令并输出原始二进制", "<命令>"),
        leaf("sideload", "通过 sideload 刷入更新包", "<OTA 包路径>"),

        // ---- 文件管理 ----
        leaf("push", "推送文件到设备", "<本地路径>", "<设备路径>"),
        leaf("pull", "从设备拉取文件", "<设备路径>", "[本地路径]"),

        // ---- 日志 ----
        node(
            "logcat", "查看设备日志",
            leaf("clear", "清空日志缓冲", "-c"),
            leaf("dump", "导出当前日志并退出", "-d"),
            leaf("buffer", "指定日志缓冲区", "-b", "<main|system|crash>"),
            leaf("format", "指定输出格式", "-v", "<brief|time|threadtime>"),
            leaf("filter", "按标签过滤", "-s", "<标签>"),
        ),

        // ---- 系统控制 ----
        node(
            "reboot", "重启设备",
            leaf("bootloader", "重启到 bootloader"),
            leaf("recovery", "重启到 recovery"),
            leaf("sideload", "重启到 sideload 模式"),
            leaf("fastboot", "重启到 fastboot"),
        ),
        leaf("bugreport", "生成完整诊断报告", "[输出路径]"),
        leaf("backup", "备份应用数据", "-f", "<输出文件>", "-apk", "<包名>"),
        leaf("restore", "从备份恢复", "<备份文件>"),

        // ---- shell 及其子命令 ----
        node("shell", "在设备上执行命令", *shellSubcommands.toTypedArray()),
    )

    /**
     * `shell` 下的子命令。
     *
     * 单独暴露出来是因为本应用里 `pm list packages` 与 `adb shell pm list packages` 等价，
     * 补全时把这一层提到根层级可以少打一层 `shell`。
     */
    val shellSubcommands: List<AdbCommandSpec> = listOf(
        shellPackageManager(),
        shellActivityManager(),
        shellSettings(),
        shellDumpsys(),
        shellWindowManager(),
        shellSvc(),
        shellInput(),
        leaf("screencap", "屏幕截图", "-p", "<输出路径>"),
        leaf("screenrecord", "录制屏幕", "--time-limit", "<秒>", "<输出路径>"),
        leaf("getprop", "读取系统属性", "[属性名]"),
        // 只保留普通 shell 可读的路径；/system/build.prop 在非 root 下会被拒绝
        leaf("cat", "查看文件内容", "/proc/cpuinfo", "/proc/meminfo", "/proc/<pid>/status"),
        leaf("content", "查询或修改 ContentProvider", "query", "insert", "delete", "update"),
        leaf("monkey", "压力测试", "-p", "<包名>", "-v", "<次数>"),
        leaf("ime", "输入法管理", "list", "set", "<输入法 ID>"),
        leaf("uiautomator", "UI 自动化", "dump", "<输出路径>"),
        leaf("top", "查看进程占用", "-n", "<次数>"),
        leaf("ps", "列出进程"),
        leaf("df", "查看磁盘空间"),
        leaf("du", "查看目录占用", "-sh", "<路径>"),
        leaf("date", "查看或设置系统时间", "<yyyyMMdd.HHmmss>"),
        leaf("ifconfig", "查看网络接口", "[接口名]"),
        leaf("ip", "查看或配置网络", "addr", "route"),
        leaf("netstat", "查看网络连接"),
        leaf("id", "查看当前用户身份"),
        leaf("pidof", "按名称查进程 PID", "<进程名>"),
    )

    private fun shellPackageManager() = node(
        "pm", "包管理",
        node(
            "list", "列出包",
            leaf("packages", "列出全部包", "-f", "-d", "-e", "-s", "-3"),
            leaf("permissions", "列出全部权限", "-g", "-d", "-u"),
            leaf("permission-groups", "列出权限组"),
            leaf("features", "列出系统特性"),
            leaf("libraries", "列出共享库"),
            leaf("instrumentation", "列出测试组件"),
        ),
        leaf(
            "install",
            "安装 APK",
            "-r", "-d", "-g", "-t", "-l", "-s",
            "--abi", "--bypass-low-target-sdk-block",
            "<APK 路径>",
        ),
        leaf("uninstall", "卸载应用", "-k", "<包名>"),
        leaf("clear", "清除应用数据", "<包名>"),
        leaf("path", "查看 APK 安装路径", "<包名>"),
        leaf("enable", "启用应用", "<包名>"),
        leaf("disable", "停用应用", "<包名>"),
        leaf("disable-user", "对当前用户停用应用", "--user", "<用户 ID>", "<包名>"),
        leaf("dump", "查看应用详细信息", "<包名>"),
        leaf("grant", "授予权限", "<包名>", "<权限>"),
        leaf("revoke", "撤销权限", "<包名>", "<权限>"),
        leaf("reset-permissions", "重置全部运行时权限"),
    )

    private fun shellActivityManager() = node(
        "am", "活动管理",
        leaf("start", "启动 Activity", "-n", "<包名/Activity>", "-a", "<action>", "-d", "<URI>"),
        leaf("startservice", "启动 Service", "-n", "<包名/Service>"),
        leaf("stopservice", "停止 Service", "-n", "<包名/Service>"),
        leaf("broadcast", "发送广播", "-a", "<action>", "--es", "<键>", "<值>"),
        leaf("force-stop", "强制停止应用", "<包名>"),
        leaf("kill", "结束进程", "<包名>"),
        leaf("send-trim-memory", "收紧内存", "<包名>", "<级别>"),
        leaf("set-debug-app", "设置调试应用", "-w", "<包名>"),
        leaf("clear-debug-app", "清除调试应用"),
        leaf("stack", "查看任务栈", "list"),
        leaf("dumpheap", "导出堆快照", "<包名>", "<输出路径>"),
        leaf("instrument", "运行 instrumentation", "-w", "<包名/组件>"),
    )

    private fun shellSettings() = node(
        "settings", "系统设置读写",
        node(
            "get", "读取设置项",
            leaf("global", "全局设置", "<键名>"),
            leaf("system", "系统设置", "<键名>"),
            leaf("secure", "安全设置", "<键名>"),
        ),
        node(
            "put", "写入设置项",
            leaf("global", "全局设置", "<键名>", "<值>"),
            leaf("system", "系统设置", "<键名>", "<值>"),
            leaf("secure", "安全设置", "<键名>", "<值>"),
        ),
        node(
            "delete", "删除设置项",
            leaf("global", "全局设置", "<键名>"),
            leaf("system", "系统设置", "<键名>"),
            leaf("secure", "安全设置", "<键名>"),
        ),
        node(
            "list", "列出设置项",
            leaf("global", "全局设置"),
            leaf("system", "系统设置"),
            leaf("secure", "安全设置"),
        ),
    )

    private fun shellDumpsys() = node(
        "dumpsys", "查看系统服务状态",
        leaf("activity", "查看活动栈与 Service", "activities", "services"),
        leaf("battery", "查看电池状况"),
        leaf("package", "查看包信息", "<包名>"),
        leaf("window", "查看窗口信息", "displays"),
        leaf("meminfo", "查看内存占用", "<包名>"),
        leaf("cpuinfo", "查看 CPU 占用"),
        leaf("diskstats", "查看磁盘统计"),
        leaf("display", "查看显示信息"),
        leaf("connectivity", "查看网络连接"),
        leaf("wifi", "查看 WiFi 状态"),
        leaf("power", "查看电源状态"),
        leaf("alarm", "查看闹钟信息"),
        leaf("audio", "查看音频状态"),
        leaf("input", "查看输入设备"),
        leaf("notification", "查看通知"),
        leaf("telephony", "查看电话信息"),
        leaf("thermal", "查看温度"),
        leaf("user", "查看用户信息"),
        leaf("statusbar", "查看状态栏"),
        leaf("hardware_properties", "查看硬件属性"),
    )

    private fun shellWindowManager() = node(
        "wm", "窗口与显示管理",
        leaf("size", "查看或设置分辨率", "<宽>x<高>", "reset"),
        leaf("density", "查看或设置屏幕密度", "<数值>", "reset"),
        leaf("overscan", "设置显示区域", "<左,上,右,下>", "reset"),
    )

    private fun shellSvc() = node(
        "svc", "系统服务开关",
        node(
            "wifi", "WiFi 开关",
            leaf("enable", "开启 WiFi"),
            leaf("disable", "关闭 WiFi"),
        ),
        node(
            "data", "移动数据开关",
            leaf("enable", "开启移动数据"),
            leaf("disable", "关闭移动数据"),
        ),
        node(
            "power", "电源控制",
            leaf("stayon", "保持唤醒", "true", "false"),
            leaf("reboot", "重启"),
        ),
        node(
            "bluetooth", "蓝牙开关",
            leaf("enable", "开启蓝牙"),
            leaf("disable", "关闭蓝牙"),
        ),
    )

    private fun shellInput() = node(
        "input", "模拟输入事件",
        leaf("keyevent", "发送按键", "KEYCODE_HOME", "KEYCODE_BACK", "KEYCODE_POWER", "KEYCODE_MENU"),
        leaf("text", "输入文本", "<文本>"),
        leaf("tap", "点击坐标", "<x>", "<y>"),
        leaf("swipe", "滑动", "<x1>", "<y1>", "<x2>", "<y2>", "[时长ms]"),
        leaf("draganddrop", "拖拽", "<x1>", "<y1>", "<x2>", "<y2>"),
        leaf("press", "按压", "<x>", "<y>"),
        leaf("roll", "滚轮", "<dx>", "<dy>"),
    )
}
