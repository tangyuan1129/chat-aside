package io.github.tangyuan1129.chataside.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Keeps the app process at foreground importance, and — more importantly —
 * notices when the accessibility service has been switched off underneath us.
 *
 * The process part targets the MIUI/HyperOS "Greezer"; the user must still grant
 * autostart / no-battery-restriction for it to hold. That is what the first
 * paragraph of this doc used to say, and it was only half the story.
 *
 * The second half, measured on a realme/ColorOS device:
 *
 *     ActivityManager: Force stopping … : o-stop(40)
 *     AccessibilityUserState: [serviceDisconnectedLocked] add mCrashedServices …
 *     AccessibilityManagerService: mEnabledServices = [无障碍菜单, …]   ← ours gone
 *
 * The OS kills the app, the accessibility service disconnects, Android records
 * that as a crash and **removes it from the enabled list**. The app then does
 * nothing at all, and nothing anywhere says why — from the user's side the app
 * simply stopped working. That happens on every ROM that aggressively kills
 * background apps, which is most of them, so making it visible is what actually
 * makes this usable beyond the developer's own phone.
 *
 * We cannot re-enable the service programmatically — Android deliberately
 * forbids that. We can, however, put a tappable notification in front of the
 * user within seconds.
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Whether the alert notification is currently up, so we only post on change. */
    private var alertShown = false

    private val watchdog = object : Runnable {
        override fun run() {
            refreshAlert()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()

        val notif: Notification = Notification.Builder(this, RUNNING_CHANNEL)
            .setContentTitle("旁白正在运行")
            .setContentText("在聊天旁读消息、给回复建议")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, notif)

        // Check once immediately: if the user opens the app while the service is
        // already off, they should learn that at once rather than after a delay.
        refreshAlert()
        handler.postDelayed(watchdog, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- watchdog

    /**
     * Posts or clears the "switched off" alert. Runs on every tick, so it must
     * stay cheap and must not touch the notification when nothing changed.
     */
    private fun refreshAlert() {
        val enabled = accessibilityServiceEnabled()
        when {
            !enabled && !alertShown -> {
                postAlert()
                alertShown = true
            }
            enabled && alertShown -> {
                manager.cancel(ALERT_ID)
                alertShown = false
            }
        }
    }

    /**
     * Whether our own accessibility service is enabled right now.
     *
     * Asks [AccessibilityManager] rather than reading the secure setting: the
     * setting can still list a service the system has actually rejected, and the
     * manager is what every vendor honours.
     */
    private fun accessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun postAlert() {
        val open = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, ALERT_CHANNEL)
            .setContentTitle("无障碍已被系统关闭，旁白已失效")
            .setContentText("系统在后台杀掉了旁白，安卓就顺手把无障碍关了。点这里重新开启。")
            .setStyle(Notification.BigTextStyle().bigText(
                "系统在后台杀掉了旁白，安卓就会把无障碍服务当成崩溃并自动关闭，" +
                    "于是界面上再也没有任何反应。\n\n" +
                    "点这条通知去重新开启无障碍；同时建议把旁白加入「自启动」和" +
                    "「省电白名单」，否则还会再发生。"
            ))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ALERT_ID, notif) }
    }

    /**
     * Two channels on purpose: a channel's importance is fixed once created, so
     * the alert cannot ride on the silent running notification's channel.
     */
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(RUNNING_CHANNEL, "旁白正在运行", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "旁白需要处理", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "无障碍被系统关闭等需要用户处理的情况" }
        )
    }

    companion object {
        private const val RUNNING_CHANNEL = "aside_running"
        private const val ALERT_CHANNEL = "aside_alert"
        private const val FOREGROUND_ID = 1
        private const val ALERT_ID = 2

        /** Cheap local check; 5s keeps the "it broke" gap short without waking anything. */
        private const val CHECK_INTERVAL_MS = 5_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
