package com.k80.chargefloat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.bootStart) return
        if (!Settings.canDrawOverlays(context)) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, FloatService::class.java))
        }
    }
}
