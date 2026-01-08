package com.walktracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.walktracker.app.service.LocationTrackingService
import com.walktracker.app.util.SharedPreferencesManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 추적이 활성화되어 있는지 확인
            val prefs = SharedPreferencesManager(context)
            val isTrackingEnabled = prefs.isTrackingEnabled()

            if (isTrackingEnabled) {
                val serviceIntent = Intent(context, LocationTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}