package com.carl.glucobro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = Preferences(context.applicationContext)
                if (preferences.isLoggedIn()) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, GlucosePollingService::class.java)
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
