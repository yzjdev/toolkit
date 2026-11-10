package app.toolkit.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 设备温度：读电池温度。
 *
 * 这是系统唯一稳定代表整机温度的单一数值来源，普通应用可直接读取电池状态
 * 粘性广播，不需要权限，也不需要特权服务。
 */
object ThermalRepository {

    /** 返回设备温度（摄氏度）；读取失败返回 null */
    fun load(context: Context): Float? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        // EXTRA_TEMPERATURE 单位为 0.1 摄氏度
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw == Int.MIN_VALUE) return null
        return raw / 10f
    }
}
