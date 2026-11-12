package app.toolkit.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.toolkit.MainActivity
import app.toolkit.R

/**
 * 传输服务的前台壳。
 *
 * HTTP 服务本身跑在 [LanShareServer] 里；这个 Service 只负责把进程提升为前台，
 * 否则用户去电脑前操作、手机锁屏后进程被冻结，浏览器那边就断了。
 * 不持有任何状态，启停都由 ViewModel 跟着服务开关走。
 */
class LanShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        startForegroundCompat(address)
        // 被系统回收后不自动重建：服务已经没了，重建一个空壳只会留下错误提示
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(address: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.lanshare_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.lanshare_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(getString(R.string.lanshare_notification_title))
            .setContentText(
                if (address.isNotEmpty()) {
                    getString(R.string.lanshare_notification_text, address)
                } else {
                    getString(R.string.lanshare_notification_text_offline)
                },
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "lan_share"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_ADDRESS = "address"

        fun start(context: Context, address: String?) {
            val intent = Intent(context, LanShareService::class.java)
                .putExtra(EXTRA_ADDRESS, address)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LanShareService::class.java)) }
        }
    }
}
