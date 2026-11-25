package com.kestrel.vpn.engine.scheduler
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
interface ResourceConstraintProvider {
    fun isConstrained(): Boolean
}
@Singleton
class DeviceResourceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : ResourceConstraintProvider {
    override fun isConstrained(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager != null) {
            if (powerManager.isPowerSaveMode) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                    return true
                }
            }
        }
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
            if (!charging && level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                if (pct <= 15) return true
            }
        }
        return false
    }
}
