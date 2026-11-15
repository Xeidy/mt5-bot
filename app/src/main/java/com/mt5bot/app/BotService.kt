package com.mt5bot.app

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
import android.os.PowerManager

/**
 * Foreground service: menjaga proses tetap hidup (dengan notifikasi permanen)
 * supaya bot terus berjalan walau aplikasi ditutup / layar mati.
 */
class BotService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.channels(this)
        val n = Notifs.service(this, "Bot berjalan…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifs.SERVICE_ID, n)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mt5bot:loop").apply {
            setReferenceCounted(false)
            acquire()
        }
        alive = true
        Engine.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        alive = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        @Volatile var alive = false
    }
}

object Notifs {
    const val SERVICE_ID = 1
    private const val CH_SERVICE = "bot"
    private const val CH_ALERT = "trades"
    private var nextId = 100

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Bot berjalan", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Trade & peringatan", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun service(c: Context, text: String): Notification =
        Notification.Builder(c, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("mt5-bot")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(c))
            .build()

    fun updateService(c: Context, text: String) {
        if (!BotService.alive) return
        c.getSystemService(NotificationManager::class.java).notify(SERVICE_ID, service(c, text))
    }

    fun alert(c: Context, text: String) {
        channels(c)
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("mt5-bot")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(nextId++, n)
    }
}
