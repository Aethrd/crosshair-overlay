package com.pure.crosshair

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Brings the overlay back after a reboot, if the user asked for that. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val prefs = Prefs(context)
        if (!prefs.startOnBoot) return
        if (!Settings.canDrawOverlays(context)) return

        // Some OEM builds block service starts this early; failing here is not worth a crash.
        runCatching { OverlayService.start(context) }
    }
}
