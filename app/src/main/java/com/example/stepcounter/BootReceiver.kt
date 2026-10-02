package com.example.stepcounter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ξεκινάει τη μέτρηση μόλις ανοίξει το κινητό ή μετά από ενημέρωση της εφαρμογής. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (StepStore(context).autoEnabled) StepService.start(context)
            }
        }
    }
}
