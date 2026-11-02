package com.faceclean.wasuporte

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

object Sys {

    fun hasNotificationAccess(ctx: Context): Boolean {
        val cn = ComponentName(ctx, SuporteListener::class.java).flattenToString()
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn) == true
    }

    fun ignoringBattery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    fun hasFilesAccess(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun appVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    fun rebindListener(ctx: Context) {
        try { NotificationListenerService.requestRebind(ComponentName(ctx, SuporteListener::class.java)) } catch (_: Throwable) {}
    }
}

/** A cada ~10 min: religa o listener se caiu, varre as notificações abertas e garante os loops rodando. */
object Watchdog {

    private const val INTERVAL_MS = 10 * 60_000L

    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 21, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, pi)
        } catch (_: Throwable) {}
    }

    fun check(ctx: Context) {
        Engine.init(ctx)
        if (Sys.hasNotificationAccess(ctx) && !SuporteListener.connected) Sys.rebindListener(ctx)
        SuporteListener.rescan()
        Engine.kick()
        Engine.kickPoll()
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pr = goAsync()
        Thread { try { Watchdog.check(context); Watchdog.schedule(context) } finally { pr.finish() } }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pr = goAsync()
        Thread { try { Watchdog.check(context); Watchdog.schedule(context) } finally { pr.finish() } }.start()
    }
}
