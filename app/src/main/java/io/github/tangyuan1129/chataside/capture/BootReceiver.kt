package io.github.tangyuan1129.chataside.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the watchdog after a reboot.
 *
 * Needed because of a bootstrapping problem the watchdog itself cannot solve: it
 * lives in [KeepAliveService], and that service used to be started only from the
 * accessibility service — so in the one situation where the alert matters (the
 * accessibility service is off), nothing was running to raise it.
 *
 * The UI now arms it too, but a reboot would still leave the app unwatched until
 * the user next opened it. This closes that gap.
 *
 * Failures are swallowed on purpose: a ROM that refuses the start should not
 * produce a crash dialog at boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { KeepAliveService.start(context) }
    }
}
