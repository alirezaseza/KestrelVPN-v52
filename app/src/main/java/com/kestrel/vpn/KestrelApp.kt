package com.kestrel.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import com.kestrel.vpn.R
import javax.inject.Inject

@HiltAndroidApp
class KestrelApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        com.kestrel.vpn.util.DiagnosticsLog.attachFile(java.io.File(filesDir, "diag.log"))
        installCrashRecorder()
        logExitReasons()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN_STATUS, getString(R.string.channel_vpn_status), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN_ALERTS, getString(R.string.channel_vpn_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Android's own record of why the app process ended last time (native crash, out-of-memory kill, ...). */
    private fun logExitReasons() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName, 0, 4).forEach { i ->
                val name = when (i.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH(java)"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
                    else -> "reason=${i.reason}"
                }
                com.kestrel.vpn.util.DiagnosticsLog.add(
                    "exit",
                    "$name status=${i.status} proc=${i.processName} rssMB=${i.rss / 1048576} desc=${i.description}"
                )
            }
        }
    }

    /** Saves the last uncaught exception so it can be copied from Settings > Copy diagnostics after a crash. */
    private fun installCrashRecorder() {
        val store = getSharedPreferences("kestrel_crash", MODE_PRIVATE)
        store.getString("last", null)?.lines()?.take(30)?.forEach {
            com.kestrel.vpn.util.DiagnosticsLog.add("last-crash", it.trim())
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = "thread=${thread.name}\n" + android.util.Log.getStackTraceString(error).take(5000)
                store.edit().putString("last", text).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CHANNEL_VPN_STATUS = "vpn_status"
        const val CHANNEL_VPN_ALERTS = "vpn_alerts"
    }
}
